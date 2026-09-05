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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
        String correlationHeader,

        /**
         * Prefixo que o roteador de borda acrescenta ao caminho.
         * <p>
         * Ele identifica a aplicação para quem está de fora e não faz parte do
         * endereço que o serviço de negócio conhece. O componente o remove antes
         * de comparar com a matriz e antes de montar o destino — sem isso, a
         * regra nunca casaria, e a rota atravessaria sem verificação.
         * <p>
         * Vazio quando o roteador já entrega o caminho sem prefixo.
         */
        @DefaultValue("")
        String contextPath
) {

    public ProxyProperties {
        interceptRules = interceptRules == null ? List.of() : List.copyOf(interceptRules);

        requirePositive(connectTimeout, "proxy.connect-timeout");
        requirePositive(readTimeout, "proxy.read-timeout");

        if (maxBodyBytes <= 0) {
            throw new IllegalArgumentException("proxy.max-body-bytes precisa ser maior que zero");
        }

        contextPath = normalize(contextPath);
    }

    /**
     * Remove o prefixo do caminho recebido.
     * <p>
     * Um caminho que não comece pelo prefixo é devolvido intacto: pode ser uma
     * chamada interna, ou o roteador pode não tê-lo acrescentado.
     */
    public String stripContextPath(String requestPath) {

        if (contextPath.isEmpty() || requestPath == null || !requestPath.startsWith(contextPath)) {
            return requestPath;
        }

        String stripped = requestPath.substring(contextPath.length());

        return stripped.isEmpty() ? "/" : stripped;
    }

    /**
     * Aceita o prefixo com ou sem barra inicial, com ou sem barra final.
     */
    private static String normalize(String value) {

        String trimmed = value == null ? "" : value.trim();

        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return "";
        }

        String withLeading = trimmed.startsWith("/") ? trimmed : "/" + trimmed;

        return withLeading.endsWith("/")
                ? withLeading.substring(0, withLeading.length() - 1)
                : withLeading;
    }

    /**
     * Uma rota verificada.
     *
     * @param path    o caminho a verificar, sem o prefixo do roteador de borda.
     *                <p>
     *                Aceita segmento variável — {@code /chaves/{id}} — e curinga
     *                de um segmento — {@code /chaves/*}. O nome dentro das chaves
     *                é livre e serve a quem lê a regra.
     *                <p>
     *                <strong>Não aceita {@code **}.</strong> Um curinga de
     *                múltiplos segmentos protege o que existe hoje e tudo que for
     *                criado depois, sem ninguém revisar.
     * @param methods os métodos verificados nesse caminho.
     */
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

        private static final PathPatternParser PARSER = new PathPatternParser();

        private static final String MULTI_SEGMENT_WILDCARD = "**";

        /**
         * Padrões já compilados, por caminho.
         * <p>
         * Compilar custa caro, e a comparação acontece em toda requisição. As
         * regras vêm da configuração e não mudam depois da subida, então o
         * conjunto de caminhos é pequeno e fixo.
         */
        private static final Map<String, PathPattern> COMPILED = new ConcurrentHashMap<>();

        public InterceptRule {
            name = name == null ? "" : name.trim();
            path = path == null ? "" : path.trim();
            journey = journey == null ? "" : journey.trim();

            methods = methods == null ? Set.of() : Set.copyOf(methods);

            requireSupportedPattern(path);
        }

        private static void requireSupportedPattern(String path) {

            if (path.contains(MULTI_SEGMENT_WILDCARD)) {
                throw new IllegalArgumentException(
                        "proxy.intercept-rules[].path não aceita '" + MULTI_SEGMENT_WILDCARD
                                + "': o curinga de múltiplos segmentos verificaria também as "
                                + "rotas criadas depois, sem revisão. Use '{variavel}' ou '*' "
                                + "para um segmento. Caminho recebido: " + path);
            }
        }

        public boolean matches(String requestPath, HttpMethod method) {

            if (path.isBlank() || requestPath == null || method == null) {
                return false;
            }
            if (!methods.contains(method)) {
                return false;
            }
            return pattern().matches(PathContainer.parsePath(requestPath));
        }

        private PathPattern pattern() {
            return COMPILED.computeIfAbsent(path, PARSER::parse);
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(field + " precisa ser maior que zero");
        }
    }
}