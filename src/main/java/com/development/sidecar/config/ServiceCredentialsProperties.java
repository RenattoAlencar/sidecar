package com.development.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;


@Validated
@ConfigurationProperties(prefix = "service-credentials")
public record ServiceCredentialsProperties(

        @NotNull(message = "service-credentials.url é obrigatório")
        URI url,

        @NotBlank(message = "service-credentials.username é obrigatório")
        String username,

        @NotBlank(message = "service-credentials.password é obrigatório")
        String password,

        @DefaultValue("")
        String hostHeader,

        @DefaultValue("30s")
        @NotNull(message = "service-credentials.refresh-skew é obrigatório")
        Duration refreshSkew,

        @DefaultValue("2s")
        @NotNull(message = "service-credentials.connect-timeout é obrigatório")
        Duration connectTimeout,

        @DefaultValue("5s")
        @NotNull(message = "service-credentials.read-timeout é obrigatório")
        Duration readTimeout
) {

    public ServiceCredentialsProperties {
        hostHeader = hostHeader == null ? "" : hostHeader.trim();

        requirePositive(connectTimeout, "service-credentials.connect-timeout");
        requirePositive(readTimeout, "service-credentials.read-timeout");
        requireNonNegative(refreshSkew, "service-credentials.refresh-skew");
    }

    @Override
    public String toString() {
        return "ServiceCredentialsProperties[url=%s, username=%s, hostHeader=%s]"
                .formatted(url, username, hostHeader);
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(field + " precisa ser maior que zero");
        }
    }

    private static void requireNonNegative(Duration value, String field) {
        if (value != null && value.isNegative()) {
            throw new IllegalArgumentException(field + " não pode ser negativo");
        }
    }
}