package com.example.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Configuration
public class RateLimitConfig {

    @Bean
    @Primary
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(principal -> "api-user:" + principal.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "api-ip:" + clientIp(exchange)));
    }

    @Bean
    KeyResolver clientIpKeyResolver() {
        return exchange -> Mono.just("auth-ip:" + clientIp(exchange));
    }

    @Bean
    KeyResolver publicIpKeyResolver() {
        return exchange -> Mono.just("public-ip:" + clientIp(exchange));
    }

    private String clientIp(org.springframework.web.server.ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress == null
                ? "unknown-client"
                : remoteAddress.getAddress().getHostAddress();
    }
}
