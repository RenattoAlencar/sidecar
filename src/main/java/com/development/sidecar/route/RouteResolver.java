package com.development.sidecar.route;

import com.development.sidecar.config.ProxyProperties;
import com.development.sidecar.config.ProxyProperties.InterceptRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;

import java.util.List;

public class RouteResolver {

    private static final Logger log = LoggerFactory.getLogger(RouteResolver.class);

    private static final String ENCODED_MARKER = "%";
    private static final String TRAVERSAL_MARKER = "..";
    private static final String DOUBLE_SEPARATOR = "//";

    private final List<InterceptRule> rules;

    public RouteResolver(ProxyProperties properties) {
        this.rules = properties.interceptRules();

        if (rules.isEmpty()) {
            log.warn("Nenhuma rota verificada configurada: todo o tráfego atravessa direto");
        }
    }

    public RouteDecision resolve(String path, HttpMethod method) {

        if (path == null || path.isBlank()) {
            return RouteDecision.reject("caminho ausente");
        }

        if (method == null) {
            return RouteDecision.reject("método ausente");
        }

        String rejection = malformed(path);

        if (rejection != null) {
            return RouteDecision.reject(rejection);
        }

        for (InterceptRule rule : rules) {
            if (rule.matches(path, method)) {
                return RouteDecision.intercept(rule);
            }
        }
        return RouteDecision.passthrough();
    }

    private static String malformed(String path) {

        if (path.contains(ENCODED_MARKER)) {
            return "caminho codificado";
        }
        if (path.contains(TRAVERSAL_MARKER)) {
            return "caminho com salto de diretório";
        }
        if (path.contains(DOUBLE_SEPARATOR)) {
            return "caminho com separador repetido";
        }
        return null;
    }
}