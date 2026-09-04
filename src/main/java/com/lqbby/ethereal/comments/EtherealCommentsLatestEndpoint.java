package com.lqbby.ethereal.comments;

import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.isNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Comment.CommentOwner;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.Ref;
import run.halo.app.extension.Unstructured;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 全局最新评论聚合端点：{@code GET comments/latest?size=5}。
 *
 * <p>主题侧边栏原本需要「1 次 posts 列表 + N 次逐条评论」的 N+1 瀑布请求，
 * 这里在后端用索引查询（fieldSelector + PageRequest）把「过滤 → 排序 → 分页」
 * 全部下推到 Halo 扩展索引引擎，仅取实际需要的 {@code size} 条，把瀑布压成 1 个请求。
 *
 * <p>响应体只保留渲染所需字段，IP / UA / owner 原始注解均不外泄。
 *
 * <p>行为可通过插件后台设置（extensions/settings.yaml，recentComments 分组）调整：
 * 默认返回条数 / 单次请求上限 / 结果缓存秒数；未保存过设置时使用内置默认值。
 */
@Component
public class EtherealCommentsLatestEndpoint implements CustomEndpoint {

    private static final String SETTING_GROUP = "recentComments";
    /** 与 settings.yaml 各字段的 value 保持一致的内置默认值。 */
    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_SIZE = 20;
    /** 匿名访问的默认结果缓存秒数（>0 才启用；0 表示实时查询）。 */
    private static final int CACHE_SECONDS = 60;
    /** subjectRef.version 缺失时的兜底版本，Halo 内置扩展多为 v1alpha1。 */
    private static final String FALLBACK_VERSION = "v1alpha1";

    private final ExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;

    /** 聚合结果服务端缓存（cacheSeconds > 0 时启用）。 */
    private record CacheEntry(long cachedAt, int maxSize, long total,
                              List<LatestCommentItem> items) {
    }

    private volatile CacheEntry cache;

    /** 设置项归一化后的生效值（null 安全，超出范围回退默认）。 */
    private record Effective(int defaultSize, int maxSize, int cacheSeconds) {
    }

    public EtherealCommentsLatestEndpoint(ExtensionClient client,
        ReactiveSettingFetcher settingFetcher) {
        this.client = client;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("comments/latest", this::latest)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.recent-comments.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> latest(ServerRequest request) {
        // ReactiveSettingFetcher 自带缓存且配置变更时自动刷新，直接调用即得最新配置；
        // 用户从未保存过设置时 ConfigMap 不存在，补一个空配置走内置默认值。
        return settingFetcher.fetch(SETTING_GROUP, RecentCommentsConfig.class)
            .onErrorResume(e -> Mono.just(new RecentCommentsConfig()))
            .defaultIfEmpty(new RecentCommentsConfig())
            .flatMap(config -> {
                Effective eff = effectiveOf(config);
                int size = parseSize(request, eff);
                return Mono.fromCallable(() -> queryLatest(eff, size))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(result -> ServerResponse.ok().bodyValue(result));
            });
    }

    private Effective effectiveOf(RecentCommentsConfig config) {
        int maxSize = clamp(config.getMaxSize(), 1, 100, MAX_SIZE);
        int defaultSize = clamp(config.getDefaultSize(), 1, maxSize, DEFAULT_SIZE);
        int cacheSeconds = clamp(config.getCacheSeconds(), 0, 3600, CACHE_SECONDS);
        return new Effective(defaultSize, maxSize, cacheSeconds);
    }

    private int clamp(Integer raw, int min, int max, int fallback) {
        if (raw == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, raw));
    }

    private int parseSize(ServerRequest request, Effective eff) {
        String raw = request.queryParam("size").orElse("");
        int size;
        try {
            size = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            size = eff.defaultSize();
        }
        if (size <= 0) {
            return eff.defaultSize();
        }
        return Math.min(size, eff.maxSize());
    }

    private LatestCommentResult queryLatest(Effective eff, int size) {
        if (eff.cacheSeconds() <= 0) {
            // 未启用缓存：直接下推一次索引查询（过滤 + 排序 + 分页），只取 size 条
            cache = null;
            ListResult<Comment> page = queryPage(size);
            List<LatestCommentItem> items = page.getItems().stream()
                .map(this::toItem)
                .toList();
            return new LatestCommentResult(size, page.getTotal(), items);
        }

        long now = System.currentTimeMillis();
        CacheEntry entry = cache;
        if (entry == null
            || now - entry.cachedAt() > eff.cacheSeconds() * 1000L
            || entry.maxSize() != eff.maxSize()) {
            // 缓存按「单次请求上限」存满量，后续请求按各自 size 切片即可命中
            ListResult<Comment> page = queryPage(eff.maxSize());
            List<LatestCommentItem> items = page.getItems().stream()
                .map(this::toItem)
                .toList();
            entry = new CacheEntry(now, eff.maxSize(), page.getTotal(), items);
            cache = entry;
        }
        List<LatestCommentItem> sliced = entry.items().size() > size
            ? entry.items().subList(0, size) : entry.items();
        return new LatestCommentResult(size, entry.total(), sliced);
    }

    /**
     * 服务端索引查询：过滤（approved 且未 hidden 且未删除）+ 按创建时间倒序排序 + 分页，
     * 全部下推到 Halo 扩展索引引擎，避免 listAll 全量读库 + 内存过滤排序。
     *
     * <p>{@code spec.approved}/{@code spec.hidden}/{@code spec.creationTime} 均由
     * Halo 为 Comment 注册了索引（见 core 的 SchemeInitializer），可直接作 fieldSelector
     * 与 sort 字段；未索引字段会被引擎拒绝。排序加 metadata.name 作稳定分页的次级键。
     */
    private ListResult<Comment> queryPage(int size) {
        var options = ListOptions.builder()
            .fieldQuery(and(
                equal("spec.approved", true),
                equal("spec.hidden", false),
                isNull("metadata.deletionTimestamp")
            ))
            .build();
        var pageRequest = PageRequestImpl.of(1, size,
            Sort.by(Sort.Order.desc("spec.creationTime"), Sort.Order.asc("metadata.name")));
        return client.listBy(Comment.class, options, pageRequest);
    }

    private LatestCommentItem toItem(Comment comment) {
        var meta = comment.getMetadata();
        var spec = comment.getSpec();
        var owner = spec.getOwner();

        String displayName = null;
        String emailHash = null;
        String avatar = null;
        if (owner != null) {
            displayName = owner.getDisplayName();
            Map<String, String> annotations = owner.getAnnotations();
            if (annotations != null) {
                avatar = annotations.get(CommentOwner.AVATAR_ANNO);
                emailHash = annotations.get(CommentOwner.EMAIL_HASH_ANNO);
            }
            // ⚠️ 直读 DB 绕过了 Halo 查询层（api.halo.run）对 owner 的加工，须手动补齐：
            //   1) Email 访客：Halo 在返回时注入 email-hash = sha256(小写邮箱) 并抹空明文 name，
            //      DB 原始数据无该注解（仅 website），故用 owner.name（明文邮箱）现算 —— 算法与
            //      CommentNextAvatarUrlResolver.forEmail 完全一致（weavatar 按 sha256 寻址）；
            //   2) User（登录用户）：Halo 会把 User 扩展的 spec.avatar 注入顶层 owner.avatar，
            //      DB owner 无该注解，须 fetch /registry/users/<name> 取 spec.avatar —— 与
            //      CommentNext userService.getUserOrGhost 行为一致。
            //  主题侧 resolveAvatar/adaptItem 无需任何改动（视觉与评论区统一）。
            if (emailHash == null
                && CommentOwner.KIND_EMAIL.equals(owner.getKind())
                && hasText(owner.getName())) {
                emailHash = sha256Hex(owner.getName().strip().toLowerCase(Locale.ROOT));
            }
            if (User.KIND.equals(owner.getKind()) && hasText(owner.getName())) {
                Optional<Map<String, Object>> userData = fetchUser(owner.getName().strip());
                if (avatar == null) {
                    avatar = userData
                        .flatMap(d -> Unstructured.getNestedValue(d, "spec", "avatar"))
                        .map(String::valueOf)
                        .filter(this::hasText)
                        .orElse(null);
                }
                if (emailHash == null) {
                    emailHash = userData
                        .flatMap(d -> Unstructured.getNestedValue(d, "spec", "email"))
                        .map(String::valueOf)
                        .filter(this::hasText)
                        .map(email -> sha256Hex(email.strip().toLowerCase(Locale.ROOT)))
                        .orElse(null);
                }
            }
        }

        String content = spec.getRaw() != null ? spec.getRaw() : spec.getContent();

        Ref ref = spec.getSubjectRef();
        SubjectRefView refView = null;
        String permalink = null;
        String subjectTitle = null;
        if (ref != null) {
            refView = new SubjectRefView(ref.getGroup(), ref.getVersion(), ref.getKind(),
                ref.getName());
            Optional<Map<String, Object>> data = fetchSubject(ref);
            permalink = data.map(d -> nestedString(d, "status", "permalink")).orElse(null);
            subjectTitle = data.map(d -> nestedString(d, "spec", "title")).orElse(null);
            // 部分扩展没有 status.permalink（Moment 只有 metadata/owner/spec/stats；
            // Plugin 扩展只有 spec/status.phase 等运行态），无法靠通用 fetchSubject 拿到前端链接，
            // 这里按主题的固定路由规则兜底拼 permalink（评论锚点 #comment 由主题侧补）。
            if (permalink == null) {
                permalink = synthesizePermalink(ref);
            }
        }

        return new LatestCommentItem(
            meta == null ? null : meta.getName(),
            displayName,
            emailHash,
            avatar,
            content,
            creationTimeOf(comment).toString(),
            refView,
            subjectTitle,
            permalink
        );
    }

    /**
     * 无 status.permalink 的扩展按主题固定路由兜底拼前端链接。
     *
     * <p>目前覆盖两类（均为独立插件提供、无通用 permalink 的扩展）：
     * <ul>
     *   <li>Moment（瞬间）→ 主题 moments.astro 详情路由 {@code /moments/{name}}；</li>
     *   <li>Plugin（插件页，如链接管理 PluginLinks）→ 主题 links.astro 路由 {@code /links}。</li>
     * </ul>
     * 其它拿不到 permalink 的 kind（如 Photo，暂无评论数据）保持 null，由主题侧回退 href="#"。
     */
    private String synthesizePermalink(Ref ref) {
        String kind = ref.getKind();
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "Moment" -> "/moments/" + ref.getName();
            case "Plugin" -> "PluginLinks".equals(ref.getName()) ? "/links" : null;
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> fetchSubject(Ref ref) {
        String name = ref.getName();
        if (name == null || name.isBlank() || ref.getKind() == null) {
            return Optional.empty();
        }
        String group = ref.getGroup() == null ? "" : ref.getGroup();
        String version =
            (ref.getVersion() == null || ref.getVersion().isBlank())
                ? FALLBACK_VERSION
                : ref.getVersion();
        var gvk = new GroupVersionKind(group, version, ref.getKind());
        return client.fetch(gvk, name)
            .map(unstructured -> (Map<String, Object>) unstructured.getData());
    }

    private String nestedString(Map<String, Object> data, String... path) {
        return Unstructured.getNestedValue(data, path)
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .orElse(null);
    }

    private Instant creationTimeOf(Comment comment) {
        var spec = comment.getSpec();
        if (spec != null && spec.getCreationTime() != null) {
            return spec.getCreationTime();
        }
        var meta = comment.getMetadata();
        if (meta != null && meta.getCreationTimestamp() != null) {
            return meta.getCreationTimestamp();
        }
        return Instant.EPOCH;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String sha256Hex(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * fetch User 扩展（owner.kind == User 时取 spec.avatar / spec.email 用）。
     * User 的 GVK group 为空串（注册表路径 /registry/users/<name>）。
     */
    private Optional<Map<String, Object>> fetchUser(String name) {
        var gvk = new GroupVersionKind(User.GROUP, User.VERSION, User.KIND);
        return client.fetch(gvk, name)
            .map(unstructured -> (Map<String, Object>) unstructured.getData());
    }
}
