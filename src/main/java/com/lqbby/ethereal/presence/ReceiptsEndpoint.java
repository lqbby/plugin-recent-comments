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
 * 已读回执端点（内存）。
 *
 * <ul>
 *   <li>{@code POST receipts/read} —— 标记某条消息被某用户读过</li>
 *   <li>{@code GET  receipts?name=xxx} —— 返回该消息的已读成员列表</li>
 * </ul>
 *
 * 状态存内存，Halo 重启清空；单实例部署足够，不落库、不依赖扩展。
 */
@Component
public class ReceiptsEndpoint implements CustomEndpoint {

    private record Reader(String id, String displayName, long readAt) {
    }

    /** messageName -> (userId -> Reader) */
    private final Map<String, Map<String, Reader>> receipts = new ConcurrentHashMap<>();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("receipts/read", this::markRead)
            .GET("receipts", this::list)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.recent-comments.halo.run/v1alpha1");
    }

    private Mono<ServerResponse> markRead(ServerRequest request) {
        return request.bodyToMono(ReadBody.class)
            .switchIfEmpty(Mono.just(new ReadBody(null, null, null)))
            .flatMap(body -> {
                String message = text(body.messageName());
                String userId = text(body.userId());
                if (message == null || userId == null) {
                    return ServerResponse.badRequest()
                        .bodyValue(Map.of("error", "messageName/userId required"));
                }
                String displayName = text(body.displayName()) == null
                    ? "访客" : body.displayName().trim();
                receipts.computeIfAbsent(message, k -> new ConcurrentHashMap<>())
                    .put(userId, new Reader(userId, displayName, System.currentTimeMillis()));
                return ServerResponse.ok().bodyValue(Map.of("ok", true));
            });
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        String message = text(request.queryParam("name").orElse(""));
        if (message == null) {
            return ServerResponse.badRequest().bodyValue(Map.of("error", "name required"));
        }
        Map<String, Reader> map = receipts.getOrDefault(message, Map.of());
        List<Map<String, Object>> readers = map.values().stream()
            .sorted(Comparator.comparingLong(Reader::readAt))
            .map(r -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", r.id());
                m.put("displayName", r.displayName());
                m.put("readAt", r.readAt());
                return m;
            })
            .toList();
        return ServerResponse.ok().bodyValue(Map.of("total", readers.size(), "items", readers));
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ReadBody(String messageName, String userId, String displayName) {
    }
}
