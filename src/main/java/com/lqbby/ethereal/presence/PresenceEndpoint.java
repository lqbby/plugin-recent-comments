package com.lqbby.ethereal.presence;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 在线状态端点（内存心跳，无需 WebSocket）。
 *
 * <ul>
 *   <li>{@code POST presence/heartbeat} —— 前端每 30s 上报一次，记录 id / 昵称 / 头像</li>
 *   <li>{@code GET  presence} —— 返回最近 60s 有心跳的用户（顺手清理过期）</li>
 * </ul>
 *
 * 状态存内存：Halo 重启即清空——在线状态本身如此，符合语义；单实例部署足够。
 * 不落库、不依赖任何扩展，卸载即消失。
 */
@Component
public class PresenceEndpoint implements CustomEndpoint {

    /** 心跳有效期：超过 60s 无心跳视为离线。 */
    private static final long TTL_MS = 60_000L;

    private record Presence(String id, String displayName, String avatar, long lastSeen) {
    }

    private final Map<String, Presence> presences = new ConcurrentHashMap<>();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("presence/heartbeat", this::heartbeat)
            .GET("presence", this::list)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.recent-comments.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> heartbeat(ServerRequest request) {
        return request.bodyToMono(HeartbeatBody.class)
            .switchIfEmpty(Mono.just(new HeartbeatBody(null, null, null)))
            .flatMap(body -> {
                String id = text(body.id());
                if (id == null) {
                    return ServerResponse.badRequest().bodyValue(Map.of("error", "id required"));
                }
                presences.put(id, new Presence(
                    id,
                    text(body.displayName()) == null ? "访客" : body.displayName().trim(),
                    text(body.avatar()),
                    System.currentTimeMillis()
                ));
                return ServerResponse.ok().bodyValue(Map.of("ok", true));
            });
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        long cutoff = System.currentTimeMillis() - TTL_MS;
        presences.entrySet().removeIf(e -> e.getValue().lastSeen() < cutoff);
        List<Map<String, Object>> users = presences.values().stream()
            .sorted(Comparator.comparingLong(Presence::lastSeen).reversed())
            .map(p -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", p.id());
                m.put("displayName", p.displayName());
                m.put("avatar", p.avatar());
                m.put("lastSeen", p.lastSeen());
                return m;
            })
            .toList();
        return ServerResponse.ok().bodyValue(Map.of("total", users.size(), "items", users));
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record HeartbeatBody(String id, String displayName, String avatar) {
    }
}
