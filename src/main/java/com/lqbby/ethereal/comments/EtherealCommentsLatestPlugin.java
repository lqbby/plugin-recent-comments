package com.lqbby.ethereal.comments;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 插件入口类。
 *
 * <p>Halo 在加载插件时以 {@link BasePlugin} 子类所在的包为组件扫描根，向下递归注册
 * 所有 {@code @Component}（含本工程的 {@code EtherealCommentsLatestEndpoint} 这个
 * CustomEndpoint）。缺少这个入口类时，Endpoint 不会被 Spring 容器注册，
 * 自定义路由 {@code /apis/api.recent-comments.halo.run/v1alpha1/comments/latest} 也就不会被挂载，
 * 未认证访问会落到未知 group 的 302 重定向逻辑（与乱写 group 表现一致）。
 */
@Component
public class EtherealCommentsLatestPlugin extends BasePlugin {

    public EtherealCommentsLatestPlugin(PluginContext context) {
        super(context);
    }
}
