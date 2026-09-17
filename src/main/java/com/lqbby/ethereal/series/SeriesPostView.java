package com.lqbby.ethereal.series;

/**
 * 系列内单篇文章的视图，只暴露主题渲染所需字段。
 *
 * @param name 文章唯一标识（{@code metadata.name}）
 * @param title 文章标题
 * @param permalink 文章链接（来自 {@code status.permalink}，缺失时回退 /archives/{slug}）
 * @param order 系列内序号（{@code metadata.annotations["seriesOrder"]} 解析结果，缺失为 null）
 * @param publishTime 发布时间（ISO 8601，缺失为 null）
 * @param cover 封面图 URL（{@code spec.cover}，缺失为 null）—— 首页「最新系列」卡片取系列最新一篇的封面
 */
public record SeriesPostView(
    String name,
    String title,
    String permalink,
    Integer order,
    String publishTime,
    String cover
) {
}
