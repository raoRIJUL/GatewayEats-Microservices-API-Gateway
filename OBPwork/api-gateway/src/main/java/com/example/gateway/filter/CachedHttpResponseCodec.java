package com.example.gateway.filter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CachedHttpResponseCodec {

    private static final int MAGIC = 0x52434831;
    private static final int MAX_HEADERS = 200;
    private static final int MAX_HEADER_TEXT_BYTES = 1024 * 1024;

    private CachedHttpResponseCodec() {
    }

    static byte[] encode(CachedHttpResponse response) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(response.body().length + 1024);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(response.statusCode());
                output.writeInt(response.headers().size());
                for (Map.Entry<String, List<String>> header : response.headers().entrySet()) {
                    writeString(output, header.getKey());
                    output.writeInt(header.getValue().size());
                    for (String value : header.getValue()) {
                        writeString(output, value);
                    }
                }
                byte[] body = response.body();
                output.writeInt(body.length);
                output.write(body);
            }
            return bytes.toByteArray();
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not encode cached HTTP response", exception);
        }
    }

    static CachedHttpResponse decode(byte[] encoded) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Unknown cached response format");
            }

            int statusCode = input.readInt();
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("Invalid cached HTTP status: " + statusCode);
            }

            int headerCount = input.readInt();
            if (headerCount < 0 || headerCount > MAX_HEADERS) {
                throw new IllegalArgumentException("Invalid cached header count: " + headerCount);
            }

            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (int index = 0; index < headerCount; index++) {
                String name = readString(input);
                int valueCount = input.readInt();
                if (valueCount < 0 || valueCount > MAX_HEADERS) {
                    throw new IllegalArgumentException("Invalid cached header value count: " + valueCount);
                }
                List<String> values = new ArrayList<>(valueCount);
                for (int valueIndex = 0; valueIndex < valueCount; valueIndex++) {
                    values.add(readString(input));
                }
                headers.put(name, values);
            }

            int bodyLength = input.readInt();
            if (bodyLength < 0 || bodyLength > encoded.length) {
                throw new IllegalArgumentException("Invalid cached body length: " + bodyLength);
            }
            byte[] body = input.readNBytes(bodyLength);
            if (body.length != bodyLength || input.available() != 0) {
                throw new IllegalArgumentException("Truncated or malformed cached response");
            }
            return new CachedHttpResponse(statusCode, headers, body);
        }
        catch (IOException exception) {
            throw new IllegalArgumentException("Could not decode cached HTTP response", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_HEADER_TEXT_BYTES) {
            throw new IllegalArgumentException("Invalid cached header text length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Truncated cached header text");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
