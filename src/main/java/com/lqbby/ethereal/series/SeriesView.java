package com.lqbby.ethereal.series;

import java.util.List;

/**
 * 单个系列及其下的文章列表（按序号升序）。
 *
 * @param name 系列名称（即文章 {@code metadata.annotations["series"]} 的值）
 * @param posts 系列内文章，按 seriesOrder 升序（未填序号排最后）
 */
public record SeriesView(String name, List<SeriesPostView> posts) {
}
