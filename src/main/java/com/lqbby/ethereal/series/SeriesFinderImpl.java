package com.lqbby.ethereal.series;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * /series/ 列表页用 {@code recentCommentsSeriesFinder.listAll()}。
 */
@Finder("recentCommentsSeriesFinder")
public class SeriesFinderImpl implements SeriesFinder {

    private static final String SERIES_ANNO = "series";
    private static final String SERIES_ORDER_ANNO = "seriesOrder";
    private static final String ARCHIVES_FALLBACK = "/archives/";

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
        for (Post post : published) {
            var metadata = post.getMetadata();
            if (metadata == null || metadata.getAnnotations() == null) {
                continue;
            }
            String seriesName = metadata.getAnnotations().get(SERIES_ANNO);
            if (seriesName == null || seriesName.isBlank()) {
                continue;
            }
            SeriesPostView view = new SeriesPostView(
                metadata.getName(),
                post.getSpec() == null ? null : post.getSpec().getTitle(),
                permalinkOf(post),
                parseOrder(metadata.getAnnotations().get(SERIES_ORDER_ANNO)),
                publishTimeOf(post)
            );
            grouped.computeIfAbsent(seriesName, key -> new ArrayList<>()).add(view);
        }

        Comparator<SeriesPostView> byOrder = Comparator
            .comparing(SeriesPostView::order, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SeriesPostView::publishTime,
                Comparator.nullsLast(Comparator.reverseOrder()));

        List<SeriesView> result = new ArrayList<>();
        for (Map.Entry<String, List<SeriesPostView>> entry : grouped.entrySet()) {
            List<SeriesPostView> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(byOrder);
            result.add(new SeriesView(entry.getKey(), sorted));
        }
        result.sort(Comparator.comparing(SeriesView::name));
        return result;
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

    private String publishTimeOf(Post post) {
        Instant instant = null;
        if (post.getSpec() != null && post.getSpec().getPublishTime() != null) {
            instant = post.getSpec().getPublishTime();
        } else if (post.getMetadata() != null && post.getMetadata().getCreationTimestamp() != null) {
            instant = post.getMetadata().getCreationTimestamp();
        }
        return instant == null ? null : instant.toString();
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
