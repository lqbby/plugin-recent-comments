package run.halo.ethereal.comments;

/**
 * 单条最新评论的脱敏视图。
 *
 * <p>刻意只暴露侧边栏渲染需要的字段：
 * 不含 {@code spec.ipAddress}、{@code spec.userAgent}，
 * 也不透传 {@code spec.owner} 原始结构（含邮箱哈希之外的其它注解）。
 */
public record LatestCommentItem(
    String name,
    String displayName,
    String emailHash,
    String avatar,
    String content,
    String creationTime,
    SubjectRefView subjectRef,
    String subjectTitle,
    String permalink
) {
}
