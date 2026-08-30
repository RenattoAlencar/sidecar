package com.development.sidecar.identity;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class JsonSupport {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private JsonSupport() {
    }

    static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    static JsonNode readTree(String json) {
        return MAPPER.readTree(json);
    }
}