package com.lqbby.ethereal;

import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 插件入口类（Ethereal 配套）。
 *
 * <p>Halo 在加载插件时以 {@link BasePlugin} 子类所在的包为组件扫描根，向下递归注册
 * 所有 {@code @Component}/{@code @Finder} 组件。本类位于根包 {@code com.lqbby.ethereal}，
 * 其下两个子包分别承载两块能力：
 * <ul>
 *   <li>{@code com.lqbby.ethereal.comments} —— 全局最新评论聚合端点；</li>
 *   <li>{@code com.lqbby.ethereal.series} —— 文章系列聚合 Finder。</li>
 * </ul>
 * 缺少这个入口类时，这些组件不会被 Spring 容器注册，端点路由与 finder 都会失效。
 */
public class EtherealCompanionPlugin extends BasePlugin {

    public EtherealCompanionPlugin(PluginContext context) {
        super(context);
    }
}
