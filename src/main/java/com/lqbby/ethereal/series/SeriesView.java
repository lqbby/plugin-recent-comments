package com.lqbby.ethereal.series;

import java.util.List;

/**
 * 单个系列及其下的文章列表（按序号升序）。
 *
 * @param name 系列名称（即文章 {@code metadata.annotations["series"]} 的值）
 * @param posts 系列内文章，按 seriesOrder 升序（未填序号排最后）
 * @param latest 系列内「发布时间最新」的那一篇（发布时间全缺失时回退为 posts 末项）；系列无文章时为 null
 */
public record SeriesView(String name, List<SeriesPostView> posts, SeriesPostView latest) {
}
