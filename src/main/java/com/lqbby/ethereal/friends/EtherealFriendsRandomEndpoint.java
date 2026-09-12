package com.lqbby.ethereal.friends;

import static run.halo.app.extension.index.query.Queries.isNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.Unstructured;
import run.halo.app.plugin.ReactiveSettingFetcher;

/**
 * 随机友链端点：{@code GET friends/random?size=6}。
 *
 * <p>动机（2026-09-13）：主题「关于我 → 我的朋友」原先由 Thymeleaf 把**全部**友链卡片
 * 写进 HTML，再由前端洗牌截前 N。友链上百条时 HTML 会随数量线性膨胀（每卡约 650B），
 * 且浏览器要解析再删掉 99% 的节点。改为服务端随机 + 按需下发后，**页面体积与友链总数无关**。
 *
 * <p>随机性：候选 name 列表可缓存（减轻匿名请求压力），但**洗牌每次请求都重做**，
 * 因此每次调用都返回不同的组合。主题侧请求时带 {@code ?_=<时间戳>} 以绕过 CDN 边缘缓存。
 *
 * <p>⚠️ 实现要点：PluginLinks 的 Link 属于其他插件，本插件**不能**引用其 Java 类
 * （Halo 插件间 classloader 隔离）。因此这里先用 {@link SchemeManager#fetch} 按 GVK
 * 取到它的 {@code Class}（仅作参数传递），只调 {@code listAllNames} 拿 name 列表，
 * 再逐个 {@code fetch(GVK, name)} 取 {@link Unstructured} —— 全程不触碰对方类型。
 *
 * <p>⚠️ 新增 @Component 必须同步登记到 {@code META-INF/plugin-components.idx}，
 * 否则 Halo 不扫描该类、路由不挂载（详见 build.gradle 注释）。
 */
@Component
public class EtherealFriendsRandomEndpoint implements CustomEndpoint {

    private static final String SETTING_GROUP = "friendsRandom";
    private static final int DEFAULT_SIZE = 6;
    private static final int MAX_SIZE = 24;
    private static final int CACHE_SECONDS = 60;

    /** PluginLinks 把 Link 注册在 core 组（不是 links.halo.run），已按其 roleTemplate 与 class 常量池核实。 */
    private static final GroupVersionKind LINKS_GVK =
        new GroupVersionKind("core.halo.run", "v1alpha1", "Link");

    private final ExtensionClient client;
    private final SchemeManager schemeManager;
    private final ReactiveSettingFetcher settingFetcher;

    private record Effective(int defaultSize, int maxSize, int cacheSeconds) {
    }

    private record NamesCache(long cachedAt, List<String> names) {
    }

    private volatile NamesCache namesCache;

    public EtherealFriendsRandomEndpoint(ExtensionClient client, SchemeManager schemeManager,
        ReactiveSettingFetcher settingFetcher) {
        this.client = client;
        this.schemeManager = schemeManager;
        this.settingFetcher = settingFetcher;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("friends/random", this::random)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.recent-comments.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> random(ServerRequest request) {
        return settingFetcher.fetch(SETTING_GROUP, FriendsRandomConfig.class)
            .onErrorResume(e -> Mono.just(new FriendsRandomConfig()))
            .defaultIfEmpty(new FriendsRandomConfig())
            .flatMap(config -> {
                Effective eff = effectiveOf(config);
                int size = parseSize(request, eff);
                return Mono.fromCallable(() -> pick(eff, size))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(result -> ServerResponse.ok().bodyValue(result));
            });
    }

    private Effective effectiveOf(FriendsRandomConfig config) {
        int maxSize = clamp(config.getMaxSize(), 1, 100, MAX_SIZE);
        int defaultSize = clamp(config.getDefaultSize(), 1, maxSize, DEFAULT_SIZE);
        int cacheSeconds = clamp(config.getCacheSeconds(), 0, 3600, CACHE_SECONDS);
        return new Effective(defaultSize, maxSize, cacheSeconds);
    }

    private int clamp(Integer raw, int min, int max, int fallback) {
        if (raw == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, raw));
    }

    private int parseSize(ServerRequest request, Effective eff) {
        String raw = request.queryParam("size").orElse("").trim();
        int size;
        try {
            size = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            size = eff.defaultSize();
        }
        if (size <= 0) {
            return eff.defaultSize();
        }
        return Math.min(size, eff.maxSize());
    }

    private FriendsRandomResult pick(Effective eff, int size) {
        List<String> names = candidateNames(eff);
        if (names.isEmpty()) {
            // PluginLinks 未安装、或一条友链都没有 —— 主题据此隐藏卡片，不报错
            return new FriendsRandomResult(0, List.of());
        }
        List<String> shuffled = new ArrayList<>(names);
        Collections.shuffle(shuffled);
        List<String> picked = shuffled.subList(0, Math.min(size, shuffled.size()));
        List<FriendItem> items = picked.stream()
            .map(name -> client.fetch(LINKS_GVK, name))
            .flatMap(Optional::stream)
            .map(this::toItem)
            .toList();
        return new FriendsRandomResult(names.size(), items);
    }

    /**
     * 取全部友链的 name（可缓存）。只缓存 name 而非对象，既省内存又避免持有
     * 其他插件的类型实例；洗牌在调用处每次重做，故缓存不影响随机性。
     */
    @SuppressWarnings("unchecked")
    private List<String> candidateNames(Effective eff) {
        long now = System.currentTimeMillis();
        NamesCache entry = namesCache;
        if (entry != null && eff.cacheSeconds() > 0
            && now - entry.cachedAt() <= eff.cacheSeconds() * 1000L) {
            return entry.names();
        }
        Scheme scheme = schemeManager.fetch(LINKS_GVK).orElse(null);
        if (scheme == null) {
            return List.of();
        }
        var options = ListOptions.builder()
            .fieldQuery(isNull("metadata.deletionTimestamp"))
            .build();
        List<String> names = client.listAllNames(
            (Class<Extension>) scheme.type(), options, Sort.unsorted());
        if (eff.cacheSeconds() > 0) {
            namesCache = new NamesCache(now, names);
        }
        return names;
    }

    private FriendItem toItem(Unstructured unstructured) {
        Map<String, Object> data = unstructured.getData();
        return new FriendItem(
            nested(data, "metadata", "name"),
            nested(data, "spec", "displayName"),
            nested(data, "spec", "url"),
            nested(data, "spec", "logo"),
            nested(data, "spec", "description")
        );
    }

    private String nested(Map<String, Object> data, String... path) {
        return Unstructured.getNestedValue(data, path)
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .orElse(null);
    }
}
