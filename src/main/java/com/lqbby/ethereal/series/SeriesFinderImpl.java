package com.lqbby.ethereal.series;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ListOptions;
import run.halo.app.theme.finders.Finder;

/**
 * 文章系列聚合 Finder 实现。
 *
 * <p>数据源是 Halo 核心 {@link Post} 扩展的 {@code metadata.annotations}：
 * {@code series}（系列名）+ {@code seriesOrder}（序号，表单值为字符串）。加载逻辑：
 * 一次 {@code client.listAll(Post.class)} 取回全部文章 → 内存过滤（已发布且未删除且带系列注解）
 * → 按系列名分组 → 组内按序号升序（未填序号排最后，再按发布时间倒序）→ 系列按名称升序。
 *
 * <p>主题模板经 {@code @Finder("recentCommentsSeriesFinder")} 直接服务端调用（SSR），
 * 无需任何前端 fetch：文章页系列导航用 {@code recentCommentsSeriesFinder.get(seriesName)}，
 * /series/ 列表页用 {@code recentCommentsSeriesFinder.listAll()}，
 * 首页「最新系列」卡片条用 {@code recentCommentsSeriesFinder.listLatest(n)}。
 *
 * <p>1.1.0 新增：{@link SeriesPostView} 带封面、{@link SeriesView} 带 {@code latest}
 * （系列内发布时间最新的一篇）、{@link #listLatest(int)}（按最近更新倒序取前 N）。
 * 三者都是**追加**语义，{@code listAll()} / {@code get()} 的既有行为与顺序未变。
 */
@Finder("recentCommentsSeriesFinder")
public class SeriesFinderImpl implements SeriesFinder {

    private static final String SERIES_ANNO = "series";
    private static final String SERIES_ORDER_ANNO = "seriesOrder";
    private static final String ARCHIVES_FALLBACK = "/archives/";

    /** listLatest 未指定上限时的默认值（首页一行 4 个可见、多出来的靠横向滚动看） */
    private static final int DEFAULT_LATEST_LIMIT = 12;
    /** listLatest 的硬上限，避免模板传个巨大值把首屏拖慢 */
    private static final int MAX_LATEST_LIMIT = 24;

    private final ExtensionClient client;

    public SeriesFinderImpl(ExtensionClient client) {
        this.client = client;
    }

    @Override
    public Flux<SeriesView> listAll() {
        return Mono.fromCallable(this::loadSeries)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Flux<SeriesView> listLatest(int limit) {
        int max = limit <= 0 ? DEFAULT_LATEST_LIMIT : Math.min(limit, MAX_LATEST_LIMIT);
        return Mono.fromCallable(() -> {
                List<SeriesView> all = new ArrayList<>(loadSeries());
                all.sort(Comparator
                    .comparing(SeriesFinderImpl::latestInstantOf,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(SeriesView::name));
                return all.size() > max ? new ArrayList<>(all.subList(0, max)) : all;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<SeriesView> get(String name) {
        if (name == null || name.isBlank()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> loadSeries().stream()
                .filter(series -> series.name().equals(name))
                .findFirst()
                .orElse(null))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private List<SeriesView> loadSeries() {
        List<Post> posts = client.listAll(Post.class, new ListOptions(), Sort.unsorted());

        // 已发布（spec.publish == true）且未删除且公开可见（visible 为 PUBLIC/空）的文章
        List<Post> published = posts.stream()
            .filter(post -> post.getSpec() != null)
            .filter(post -> Boolean.TRUE.equals(post.getSpec().getPublish()))
            .filter(post -> !post.isDeleted())
            .filter(post -> Post.isPublic(post.getSpec()))
            .toList();

        Map<String, List<SeriesPostView>> grouped = new LinkedHashMap<>();
        // 系列 → 「发布时间最新」那篇（用于 SeriesView.latest 与 listLatest 的排序键）
        Map<String, SeriesPostView> latestView = new HashMap<>();
        Map<String, Instant> latestInstant = new HashMap<>();

        for (Post post : published) {
            var metadata = post.getMetadata();
            if (metadata == null || metadata.getAnnotations() == null) {
                continue;
            }
            String seriesName = metadata.getAnnotations().get(SERIES_ANNO);
            if (seriesName == null || seriesName.isBlank()) {
                continue;
            }
            Instant publishInstant = publishInstantOf(post);
            SeriesPostView view = new SeriesPostView(
                metadata.getName(),
                post.getSpec() == null ? null : post.getSpec().getTitle(),
                permalinkOf(post),
                parseOrder(metadata.getAnnotations().get(SERIES_ORDER_ANNO)),
                publishInstant == null ? null : publishInstant.toString(),
                post.getSpec() == null ? null : post.getSpec().getCover()
            );
            grouped.computeIfAbsent(seriesName, key -> new ArrayList<>()).add(view);

            if (publishInstant != null) {
                Instant current = latestInstant.get(seriesName);
                if (current == null || publishInstant.isAfter(current)) {
                    latestInstant.put(seriesName, publishInstant);
                    latestView.put(seriesName, view);
                }
            }
        }

        Comparator<SeriesPostView> byOrder = Comparator
            .comparing(SeriesPostView::order, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SeriesPostView::publishTime,
                Comparator.nullsLast(Comparator.reverseOrder()));

        List<SeriesView> result = new ArrayList<>();
        for (Map.Entry<String, List<SeriesPostView>> entry : grouped.entrySet()) {
            List<SeriesPostView> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(byOrder);
            // latest 回退：全系列都没发布时间时，取序号最大的那篇（够卡片用，不至于开天窗）
            SeriesPostView latest = latestView.get(entry.getKey());
            if (latest == null && !sorted.isEmpty()) {
                latest = sorted.get(sorted.size() - 1);
            }
            result.add(new SeriesView(entry.getKey(), sorted, latest));
        }
        result.sort(Comparator.comparing(SeriesView::name));
        return result;
    }

    /** 取系列的排序键（最新一篇的发布时间）；缺失返回 null（由调用方 nullsLast 处理） */
    private static Instant latestInstantOf(SeriesView series) {
        SeriesPostView latest = series.latest();
        if (latest == null || latest.publishTime() == null) {
            return null;
        }
        try {
            return Instant.parse(latest.publishTime());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String permalinkOf(Post post) {
        if (post.getStatus() != null && post.getStatus().getPermalink() != null
            && !post.getStatus().getPermalink().isBlank()) {
            return post.getStatus().getPermalink();
        }
        String slug = post.getSpec() == null ? null : post.getSpec().getSlug();
        if (slug == null || slug.isBlank()) {
            return null;
        }
        return ARCHIVES_FALLBACK + slug;
    }

    private Instant publishInstantOf(Post post) {
        if (post.getSpec() != null && post.getSpec().getPublishTime() != null) {
            return post.getSpec().getPublishTime();
        }
        if (post.getMetadata() != null && post.getMetadata().getCreationTimestamp() != null) {
            return post.getMetadata().getCreationTimestamp();
        }
        return null;
    }

    private Integer parseOrder(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
