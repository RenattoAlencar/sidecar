package com.development.sidecar.contract;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class ChannelResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(ChannelResponseWriter.class);

    public static final String AUTHORIZATION_REQUIRED_HEADER = "x-authz-required";

    private static final String ERROR_FIELD = "error";
    private static final String CORRELATION_FIELD = "correlationId";

    private static final int MAX_HEADER_VALUE_LENGTH = 64;

    private final ObjectMapper objectMapper;
    private final String correlationHeader;

    public ChannelResponseWriter(ObjectMapper objectMapper, String correlationHeader) {
        this.objectMapper = objectMapper;
        this.correlationHeader = correlationHeader;
    }

    public void challenge(HttpServletResponse response,
                          ChallengeResponse challenge,
                          String correlationId) throws IOException {

        response.setHeader(AUTHORIZATION_REQUIRED_HEADER, "true");

        write(response, HttpStatus.PRECONDITION_REQUIRED, challenge, correlationId);
    }

    public void authorized(HttpServletResponse response,
                           TokenRefResponse authorized,
                           String correlationId) throws IOException {

        write(response, HttpStatus.OK, authorized, correlationId);
    }

    public void error(HttpServletResponse response,
                      HttpStatus status,
                      String error,
                      String correlationId) throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(ERROR_FIELD, error);
        body.put(CORRELATION_FIELD, correlationId);

        write(response, status, body, correlationId);
    }

    private void write(HttpServletResponse response,
                       HttpStatus status,
                       Object body,
                       String correlationId) throws IOException {

        if (response.isCommitted()) {
            log.warn("Resposta já iniciada, o componente não pôde escrever: status={}",
                    status.value());
            return;
        }

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(correlationHeader, safeHeaderValue(correlationId));

        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cache-Control", "no-store");

        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.flushBuffer();
    }

    private static String safeHeaderValue(String value) {

        if (value == null) {
            return "";
        }
        int length = Math.min(value.length(), MAX_HEADER_VALUE_LENGTH);

        StringBuilder clean = new StringBuilder(length);

        for (int index = 0; index < length; index++) {
            char current = value.charAt(index);

            boolean allowed = Character.isLetterOrDigit(current)
                    || current == '-'
                    || current == '_';

            if (allowed) {
                clean.append(current);
            }
        }
        return clean.toString();
    }
}