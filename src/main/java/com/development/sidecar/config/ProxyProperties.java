package com.development.sidecar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

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
            String journey,

            PathPattern compiled
    ) {

        private static final PathPatternParser PARSER = new PathPatternParser();

        private static final String MULTI_SEGMENT_WILDCARD = "**";

        public InterceptRule(String name, String path, Set<HttpMethod> methods, String journey) {
            this(name, path, methods, journey, compile(path));
        }

        public InterceptRule {
            name = name == null ? "" : name.trim();
            path = path == null ? "" : path.trim();
            journey = journey == null ? "" : journey.trim();

            methods = methods == null ? Set.of() : Set.copyOf(methods);
        }

        private static PathPattern compile(String path) {

            String trimmed = path == null ? "" : path.trim();

            if (trimmed.isBlank()) {
                return null;
            }

            if (trimmed.contains(MULTI_SEGMENT_WILDCARD)) {
                throw new IllegalArgumentException(
                        "proxy.intercept-rules[].path não aceita '" + MULTI_SEGMENT_WILDCARD
                                + "': o curinga de múltiplos segmentos verificaria também as "
                                + "rotas criadas depois, sem revisão. Use '{variavel}' ou '*' "
                                + "para um segmento. Caminho recebido: " + trimmed);
            }

            try {
                return PARSER.parse(trimmed);

            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "proxy.intercept-rules[].path não é um caminho válido: " + trimmed, e);
            }
        }

        public boolean matches(String requestPath, HttpMethod method) {

            if (compiled == null || requestPath == null || method == null) {
                return false;
            }
            if (!methods.contains(method)) {
                return false;
            }
            return compiled.matches(PathContainer.parsePath(requestPath));
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(field + " precisa ser maior que zero");
        }
    }
}