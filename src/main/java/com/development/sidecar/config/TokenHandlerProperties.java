package com.development.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "token-handler")
public record TokenHandlerProperties(

        @NotNull(message = "token-handler.url é obrigatório")
        URI url,

        @DefaultValue("X-Token-Ref")
        @NotBlank(message = "token-handler.token-ref-header é obrigatório")
        String tokenRefHeader,

        @DefaultValue("2s")
        @NotNull(message = "token-handler.connect-timeout é obrigatório")
        Duration connectTimeout,

        @DefaultValue("5s")
        @NotNull(message = "token-handler.read-timeout é obrigatório")
        Duration readTimeout
) {

    public TokenHandlerProperties {
        tokenRefHeader = tokenRefHeader == null ? "" : tokenRefHeader.trim();

        requirePositive(connectTimeout, "token-handler.connect-timeout");
        requirePositive(readTimeout, "token-handler.read-timeout");
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(field + " precisa ser maior que zero");
        }
    }
}