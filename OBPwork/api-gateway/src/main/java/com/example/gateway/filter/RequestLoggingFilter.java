package com.example.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startedAt = System.nanoTime();
        String correlationId = correlationId(exchange);
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CORRELATION_HEADER, correlationId))
                .build();
        ServerWebExchange updatedExchange = exchange.mutate().request(request).build();

        return updatedExchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("anonymous")
                .flatMap(username -> chain.filter(updatedExchange)
                        .doFinally(signal -> logRequest(
                                updatedExchange, username, correlationId, startedAt)));
    }

    private String correlationId(ServerWebExchange exchange) {
        String supplied = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
        if (supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    private void logRequest(
            ServerWebExchange exchange,
            String username,
            String correlationId,
            long startedAt
    ) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? "unmatched" : route.getId();
        String status = exchange.getResponse().getStatusCode() == null
                ? "unknown"
                : Integer.toString(exchange.getResponse().getStatusCode().value());

        log.info(
                "gateway_request correlationId={} user={} method={} path={} route={} status={} durationMs={}",
                correlationId,
                username,
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                routeId,
                status,
                durationMs
        );
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
