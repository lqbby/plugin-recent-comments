package run.halo.ethereal.comments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
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
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Ref;
import run.halo.app.extension.Unstructured;

/**
 * 全局最新评论聚合端点：{@code GET comments/latest?size=5}。
 *
 * <p>主题侧边栏原本需要「1 次 posts 列表 + N 次逐条评论」的 N+1 瀑布请求，
 * 这里在后端一次性遍历 Comment 并按创建时间倒序截取，把瀑布压成 1 个请求。
 *
 * <p>响应体只保留渲染所需字段，IP / UA / owner 原始注解均不外泄。
 */
@Component
public class EtherealCommentsLatestEndpoint implements CustomEndpoint {

    private static final int DEFAULT_SIZE = 5;
    private static final int MAX_SIZE = 20;
    /** subjectRef.version 缺失时的兜底版本，Halo 内置扩展多为 v1alpha1。 */
    private static final String FALLBACK_VERSION = "v1alpha1";

    private final ExtensionClient client;

    public EtherealCommentsLatestEndpoint(ExtensionClient client) {
        this.client = client;
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
        int size = parseSize(request);
        return Mono.fromCallable(() -> queryLatest(size))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private int parseSize(ServerRequest request) {
        String raw = request.queryParam("size").orElse("");
        int size;
        try {
            size = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            size = DEFAULT_SIZE;
        }
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    private LatestCommentResult queryLatest(int size) {
        var options = ListOptions.builder()
            .andQuery(ExtensionUtil.notDeleting())
            .build();
        List<Comment> all = client.listAll(Comment.class, options, Sort.unsorted());

        List<Comment> visible = all.stream()
            .filter(comment -> comment.getSpec() != null)
            .filter(comment -> Boolean.TRUE.equals(comment.getSpec().getApproved()))
            .filter(comment -> !Boolean.TRUE.equals(comment.getSpec().getHidden()))
            .sorted(Comparator.<Comment, Instant>comparing(this::creationTimeOf).reversed())
            .toList();

        List<LatestCommentItem> items = visible.stream()
            .limit(size)
            .map(this::toItem)
            .toList();

        return new LatestCommentResult(size, visible.size(), items);
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
