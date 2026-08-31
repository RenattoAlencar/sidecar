package com.development.sidecar.contract;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;


public class ChallengeAnswerReader {

    private final ObjectMapper objectMapper;

    public ChallengeAnswerReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String read(byte[] body) {

        if (body == null || body.length == 0) {
            return null;
        }
        try {
            Envelope envelope = objectMapper.readValue(
                    new String(body, StandardCharsets.UTF_8), Envelope.class);

            String response = envelope == null || envelope.authz() == null
                    ? null
                    : envelope.authz().response();

            return response == null || response.isBlank() ? null : response;

        } catch (Exception e) {
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope(Authz authz) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Authz(String response) {
        }
    }
}