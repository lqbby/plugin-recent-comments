package com.lqbby.ethereal.series;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文章系列聚合 Finder 接口。
 *
 * <p>系列（series）是文章 {@code metadata.annotations} 上的两个自定义字段：
 * {@code series}（系列名称）+ {@code seriesOrder}（系列内序号，字符串）。本 Finder 把所有
 * 已发布且带 {@code series} 注解的文章按系列分组、按序号排序后暴露给主题服务端渲染，
 * 供「文章页系列导航」与「/series/ 系列列表页」直接调用。
 */
public interface SeriesFinder {

    /**
     * 返回全部系列，按系列名升序；每个系列内的文章按 {@code seriesOrder} 升序
     * （未填序号的排最后，再按发布时间倒序）。
     */
    Flux<SeriesView> listAll();

    /**
     * 按系列名取单个系列；不存在时返回空 {@link Mono}。
     */
    Mono<SeriesView> get(String name);
}
