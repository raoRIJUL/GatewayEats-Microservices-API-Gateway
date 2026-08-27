package com.example.gateway.filter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachedHttpResponseCodecTest {

    @Test
    void roundTripsStatusHeadersAndBinaryBody() {
        byte[] body = "{\"products\":[1,2,3]}".getBytes(StandardCharsets.UTF_8);
        CachedHttpResponse original = new CachedHttpResponse(
                200,
                Map.of(
                        "Content-Type", List.of("application/json"),
                        "Cache-Control", List.of("public,max-age=30")),
                body);

        CachedHttpResponse decoded = CachedHttpResponseCodec.decode(
                CachedHttpResponseCodec.encode(original));

        assertThat(decoded.statusCode()).isEqualTo(200);
        assertThat(decoded.headers()).containsEntry("Content-Type", List.of("application/json"));
        assertThat(decoded.body()).isEqualTo(body);
    }

    @Test
    void rejectsMalformedPayload() {
        assertThatThrownBy(() -> CachedHttpResponseCodec.decode(new byte[]{1, 2, 3, 4}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
