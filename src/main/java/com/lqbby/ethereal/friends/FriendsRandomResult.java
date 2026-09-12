package com.lqbby.ethereal.friends;

import java.util.List;

/**
 * {@code GET friends/random} 的响应体。
 *
 * @param total 站点当前**全部**友链条数（供主题决定是否显示「更多」入口等）
 * @param items 本次命中的随机条目（条数 = 请求 size，且不超过 total）
 */
public record FriendsRandomResult(
    int total,
    List<FriendItem> items
) {
}
