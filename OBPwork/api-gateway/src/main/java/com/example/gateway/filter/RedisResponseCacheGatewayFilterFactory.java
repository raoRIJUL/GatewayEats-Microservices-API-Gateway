package com.example.gateway.filter;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.HasRouteId;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class RedisResponseCacheGatewayFilterFactory
        extends AbstractGatewayFilterFactory<RedisResponseCacheGatewayFilterFactory.Config> {

    public static final String CACHE_HEADER = "X-Gateway-Cache";

    private static final Logger log = LoggerFactory.getLogger(RedisResponseCacheGatewayFilterFactory.class);
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    private static final DataSize DEFAULT_MAX_ENTRY_SIZE = DataSize.ofMegabytes(1);
    private static final Set<Integer> CACHEABLE_STATUSES = Set.of(
            HttpStatus.OK.value(),
            HttpStatus.PARTIAL_CONTENT.value(),
            HttpStatus.MOVED_PERMANENTLY.value());
    private static final Set<String> SUPPORTED_VARY_HEADERS = Set.of(
            HttpHeaders.ACCEPT.toLowerCase(Locale.ROOT),
            HttpHeaders.ACCEPT_ENCODING.toLowerCase(Locale.ROOT),
            HttpHeaders.ACCEPT_LANGUAGE.toLowerCase(Locale.ROOT),
            HttpHeaders.AUTHORIZATION.toLowerCase(Locale.ROOT),
            HttpHeaders.COOKIE.toLowerCase(Locale.ROOT),
            HttpHeaders.IF_RANGE.toLowerCase(Locale.ROOT),
            HttpHeaders.RANGE.toLowerCase(Locale.ROOT));
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS = Set.of(
            HttpHeaders.CONNECTION.toLowerCase(Locale.ROOT),
            HttpHeaders.CONTENT_LENGTH.toLowerCase(Locale.ROOT),
            HttpHeaders.DATE.toLowerCase(Locale.ROOT),
            "keep-alive",
            HttpHeaders.PROXY_AUTHENTICATE.toLowerCase(Locale.ROOT),
            HttpHeaders.PROXY_AUTHORIZATION.toLowerCase(Locale.ROOT),
            HttpHeaders.SET_COOKIE.toLowerCase(Locale.ROOT),
            HttpHeaders.TE.toLowerCase(Locale.ROOT),
            HttpHeaders.TRAILER.toLowerCase(Locale.ROOT),
            HttpHeaders.TRANSFER_ENCODING.toLowerCase(Locale.ROOT),
            HttpHeaders.UPGRADE.toLowerCase(Locale.ROOT),
            CACHE_HEADER.toLowerCase(Locale.ROOT));

    private final ReactiveRedisTemplate<String, byte[]> redis;

    public RedisResponseCacheGatewayFilterFactory(
            @Qualifier("responseCacheRedisTemplate") ReactiveRedisTemplate<String, byte[]> redis
    ) {
        super(Config.class);
        this.redis = redis;
    }

    @Override
    public GatewayFilter apply(Config config) {
        Duration ttl = config.getTimeToLive() == null ? DEFAULT_TTL : config.getTimeToLive();
        DataSize configuredMaxEntrySize = config.getMaxEntrySize() == null
                ? DEFAULT_MAX_ENTRY_SIZE
                : config.getMaxEntrySize();
        int maxEntryBytes = Math.toIntExact(Math.min(configuredMaxEntrySize.toBytes(), Integer.MAX_VALUE));
        String routeId = config.getRouteId() == null ? "unknown-route" : config.getRouteId();

        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("RedisResponseCache TTL must be greater than zero");
        }
        if (maxEntryBytes <= 0) {
            throw new IllegalArgumentException("RedisResponseCache maximum entry size must be greater than zero");
        }

        return (exchange, chain) -> {
            if (!isRequestCacheable(exchange.getRequest())) {
                return chain.filter(exchange);
            }

            return resolveCacheKey(exchange, routeId)
                    .flatMap(key -> readFromCache(exchange.getRequest(), key)
                            .flatMap(encoded -> serveCached(exchange, key, encoded))
                            .filter(Boolean::booleanValue)
                            .switchIfEmpty(Mono.defer(() -> forwardAndCache(
                                    exchange, chain, key, ttl, maxEntryBytes, routeId).thenReturn(false)))
                            .then());
        };
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("timeToLive", "maxEntrySize");
    }

    private Mono<byte[]> readFromCache(ServerHttpRequest request, String key) {
        if (hasDirective(request.getHeaders(), "no-cache")) {
            return Mono.empty();
        }
        return redis.opsForValue()
                .get(key)
                .onErrorResume(exception -> {
                    log.warn("Redis response-cache read failed; continuing without cache", exception);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> serveCached(ServerWebExchange exchange, String key, byte[] encoded) {
        CachedHttpResponse cached;
        try {
            cached = CachedHttpResponseCodec.decode(encoded);
        }
        catch (RuntimeException exception) {
            log.warn("Discarding malformed Redis response-cache entry key={}", key, exception);
            return redis.delete(key)
                    .onErrorResume(ignored -> Mono.empty())
                    .thenReturn(false);
        }

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(cached.statusCode()));
        cached.headers().forEach((name, values) -> response.getHeaders().put(name, new ArrayList<>(values)));
        response.getHeaders().set(CACHE_HEADER, "HIT");
        byte[] body = cached.body();
        response.getHeaders().setContentLength(body.length);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
                .thenReturn(true);
    }

    private Mono<Void> forwardAndCache(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String key,
            Duration ttl,
            int maxEntryBytes,
            String routeId
    ) {
        exchange.getResponse().getHeaders().set(CACHE_HEADER, "MISS");
        CachingResponseDecorator response = new CachingResponseDecorator(
                exchange, key, ttl, maxEntryBytes, routeId);
        return chain.filter(exchange.mutate().response(response).build());
    }

    private boolean isRequestCacheable(ServerHttpRequest request) {
        return HttpMethod.GET.equals(request.getMethod())
                && request.getHeaders().getContentLength() <= 0
                && !hasDirective(request.getHeaders(), "no-store")
                && !hasDirective(request.getHeaders(), "private");
    }

    private boolean isResponseCacheable(ServerHttpResponse response) {
        HttpStatusCode status = response.getStatusCode();
        if (status == null || !CACHEABLE_STATUSES.contains(status.value())) {
            return false;
        }
        HttpHeaders headers = response.getHeaders();
        return !hasDirective(headers, "no-store")
                && !hasDirective(headers, "private")
                && !hasDirective(headers, "no-cache")
                && !hasUnsupportedVary(headers)
                && !headers.containsHeader(HttpHeaders.SET_COOKIE);
    }

    private boolean hasUnsupportedVary(HttpHeaders headers) {
        return headers.getOrEmpty(HttpHeaders.VARY).stream()
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .anyMatch(value -> !SUPPORTED_VARY_HEADERS.contains(value));
    }

    private boolean hasDirective(HttpHeaders headers, String expected) {
        String cacheControl = headers.getCacheControl();
        if (cacheControl == null || cacheControl.isBlank()) {
            return false;
        }
        return List.of(cacheControl.toLowerCase(Locale.ROOT).split(",")).stream()
                .map(String::trim)
                .anyMatch(value -> value.equals(expected) || value.startsWith(expected + "="));
    }

    private Mono<String> resolveCacheKey(ServerWebExchange exchange, String routeId) {
        return exchange.getPrincipal()
                .map(Principal::getName)
                .defaultIfEmpty("anonymous")
                .map(principal -> {
                    ServerHttpRequest request = exchange.getRequest();
                    String rawKey = String.join("\n",
                            routeId,
                            request.getMethod().name(),
                            request.getURI().toString(),
                            principal,
                            header(request, HttpHeaders.AUTHORIZATION),
                            header(request, HttpHeaders.COOKIE),
                            header(request, HttpHeaders.ACCEPT),
                            header(request, HttpHeaders.ACCEPT_LANGUAGE),
                            header(request, HttpHeaders.ACCEPT_ENCODING),
                            header(request, HttpHeaders.RANGE),
                            header(request, HttpHeaders.IF_RANGE));
                    return "gateway:response-cache:" + routeId + ":" + sha256(rawKey);
                });
    }

    private String header(ServerHttpRequest request, String name) {
        return String.join(",", request.getHeaders().getOrEmpty(name));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private Map<String, List<String>> cacheableHeaders(HttpHeaders headers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (!EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                result.put(name, List.copyOf(values));
            }
        });
        return result;
    }

    private final class CachingResponseDecorator extends ServerHttpResponseDecorator {

        private final String key;
        private final Duration ttl;
        private final int maxEntryBytes;
        private final String routeId;

        private CachingResponseDecorator(
                ServerWebExchange exchange,
                String key,
                Duration ttl,
                int maxEntryBytes,
                String routeId
        ) {
            super(exchange.getResponse());
            this.key = key;
            this.ttl = ttl;
            this.maxEntryBytes = maxEntryBytes;
            this.routeId = routeId;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            Duration effectiveTtl = effectiveTtl(getHeaders(), ttl);
            if (!isResponseCacheable(this) || effectiveTtl.isZero()) {
                return super.writeWith(body);
            }

            BoundedBodyCollector collector = new BoundedBodyCollector(maxEntryBytes);
            Flux<DataBuffer> observedBody = Flux.from(body)
                    .map(buffer -> (DataBuffer) buffer)
                    .doOnNext(collector::accept);

            return super.writeWith(observedBody)
                    .then(Mono.defer(() -> collector.body()
                            .map(bytes -> store(bytes, effectiveTtl))
                            .orElseGet(Mono::empty)));
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            return super.writeAndFlushWith(body);
        }

        private Mono<Void> store(byte[] body, Duration effectiveTtl) {
            CachedHttpResponse response = new CachedHttpResponse(
                    getStatusCode().value(), cacheableHeaders(getHeaders()), body);
            byte[] encoded = CachedHttpResponseCodec.encode(response);
            if (encoded.length > maxEntryBytes) {
                log.debug("Skipping Redis response-cache entry route={} encodedBytes={} limitBytes={}",
                        routeId, encoded.length, maxEntryBytes);
                return Mono.empty();
            }

            return redis.opsForValue()
                    .set(key, encoded, effectiveTtl)
                    .doOnNext(saved -> log.debug(
                            "Stored Redis response-cache entry route={} bytes={} ttl={}",
                            routeId, encoded.length, effectiveTtl))
                    .onErrorResume(exception -> {
                        log.warn("Redis response-cache write failed; response was still served", exception);
                        return Mono.empty();
                    })
                    .then();
        }
    }

    private Duration effectiveTtl(HttpHeaders headers, Duration configuredTtl) {
        Long sharedMaxAge = directiveSeconds(headers, "s-maxage");
        Long maxAge = sharedMaxAge == null ? directiveSeconds(headers, "max-age") : sharedMaxAge;
        if (maxAge == null) {
            return configuredTtl;
        }
        if (maxAge <= 0) {
            return Duration.ZERO;
        }
        Duration upstreamTtl = Duration.ofSeconds(maxAge);
        return upstreamTtl.compareTo(configuredTtl) < 0 ? upstreamTtl : configuredTtl;
    }

    private Long directiveSeconds(HttpHeaders headers, String name) {
        String cacheControl = headers.getCacheControl();
        if (cacheControl == null || cacheControl.isBlank()) {
            return null;
        }
        String prefix = name.toLowerCase(Locale.ROOT) + "=";
        for (String part : cacheControl.toLowerCase(Locale.ROOT).split(",")) {
            String directive = part.trim();
            if (directive.startsWith(prefix)) {
                String rawValue = directive.substring(prefix.length()).replace("\"", "").trim();
                try {
                    return Long.parseLong(rawValue);
                }
                catch (NumberFormatException exception) {
                    return 0L;
                }
            }
        }
        return null;
    }

    private static final class BoundedBodyCollector {

        private final int maximumBytes;
        private ByteArrayOutputStream output;
        private boolean exceeded;

        private BoundedBodyCollector(int maximumBytes) {
            this.maximumBytes = maximumBytes;
            this.output = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
        }

        private void accept(DataBuffer buffer) {
            if (exceeded) {
                return;
            }

            try (DataBuffer.ByteBufferIterator buffers = buffer.readableByteBuffers()) {
                while (buffers.hasNext()) {
                    ByteBuffer bytes = buffers.next().asReadOnlyBuffer();
                    if ((long) output.size() + bytes.remaining() > maximumBytes) {
                        exceeded = true;
                        output = null;
                        return;
                    }

                    byte[] chunk = new byte[bytes.remaining()];
                    bytes.get(chunk);
                    output.writeBytes(chunk);
                }
            }
        }

        private java.util.Optional<byte[]> body() {
            return exceeded ? java.util.Optional.empty() : java.util.Optional.of(output.toByteArray());
        }
    }

    public static class Config implements HasRouteId {

        private Duration timeToLive;
        private DataSize maxEntrySize;
        private String routeId;

        public Duration getTimeToLive() {
            return timeToLive;
        }

        public void setTimeToLive(Duration timeToLive) {
            this.timeToLive = timeToLive;
        }

        public DataSize getMaxEntrySize() {
            return maxEntrySize;
        }

        public void setMaxEntrySize(DataSize maxEntrySize) {
            this.maxEntrySize = maxEntrySize;
        }

        @Override
        public String getRouteId() {
            return routeId;
        }

        @Override
        public void setRouteId(String routeId) {
            this.routeId = routeId;
        }
    }
}
