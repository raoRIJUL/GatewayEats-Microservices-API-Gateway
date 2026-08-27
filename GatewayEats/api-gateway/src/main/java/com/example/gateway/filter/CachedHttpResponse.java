package com.example.gateway.filter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record CachedHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        byte[] body
) {
    CachedHttpResponse {
        Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
        headers.forEach((name, values) -> copiedHeaders.put(name, List.copyOf(values)));
        headers = Map.copyOf(copiedHeaders);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
