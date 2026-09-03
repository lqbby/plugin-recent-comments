package run.halo.ethereal.comments;

import java.util.List;

/**
 * {@code comments/latest} 的响应体。
 *
 * @param size 本次请求的条数上限
 * @param total 全站可见评论总数（approved 且未 hidden，不限本次返回条数）
 * @param items 按创建时间倒序的评论列表
 */
public record LatestCommentResult(int size, int total, List<LatestCommentItem> items) {
}
