package com.development.sidecar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Validated
@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(

        @NotNull(message = "proxy.target é obrigatório")
        URI target,

        @DefaultValue("2s")
        @NotNull(message = "proxy.connect-timeout é obrigatório")
        Duration connectTimeout,

        @DefaultValue("10s")
        @NotNull(message = "proxy.read-timeout é obrigatório")
        Duration readTimeout,

        @DefaultValue("2097152")
        long maxBodyBytes,

        @Valid
        @DefaultValue
        List<InterceptRule> interceptRules,

        @DefaultValue("x-correlation-id")
        @NotBlank(message = "proxy.correlation-header é obrigatório")
        String correlationHeader
) {

    public ProxyProperties {
        interceptRules = interceptRules == null ? List.of() : List.copyOf(interceptRules);

        requirePositive(connectTimeout, "proxy.connect-timeout");
        requirePositive(readTimeout, "proxy.read-timeout");

        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("proxy.max-body-bytes precisa ser maior que zero");
        }
    }

    public record InterceptRule(

            @NotBlank(message = "proxy.intercept-rules[].name é obrigatório")
            String name,

            @NotBlank(message = "proxy.intercept-rules[].path é obrigatório")
            String path,

            @NotEmpty(message = "proxy.intercept-rules[].methods é obrigatório")
            Set<HttpMethod> methods,

            @NotBlank(message = "proxy.intercept-rules[].journey é obrigatório")
            String journey
    ) {

        public InterceptRule {
            name = name == null ? "" : name.trim();
            path = path == null ? "" : path.trim();
            journey = journey == null ? "" : journey.trim();

            methods = methods == null ? Set.of() : Set.copyOf(methods);
        }

        public boolean matches(String requestPath, HttpMethod method) {
            return path.equals(requestPath) && methods.contains(method);
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(field + " precisa ser maior que zero");
        }
    }
}