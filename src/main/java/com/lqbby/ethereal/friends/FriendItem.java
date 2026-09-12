package com.lqbby.ethereal.friends;

/**
 * 单条友链的精简视图（只保留主题渲染「我的朋友」卡片所需字段）。
 *
 * <p>刻意不外泄 {@code priority} / {@code rss} / {@code verification} / 分组名等
 * 内部字段，与 {@code LatestCommentItem} 对评论的处理口径一致。
 */
public record FriendItem(
    String name,
    String displayName,
    String url,
    String logo,
    String description
) {
}
