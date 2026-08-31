package com.development.sidecar.route;

import com.development.sidecar.config.ProxyProperties;
import com.development.sidecar.config.ProxyProperties.InterceptRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteResolverTest {

    private static final String PROTECTED_PATH = "/api/v1/pix/transferencia";
    private static final String JOURNEY = "jornada-transacional";
    private static final String RULE_NAME = "pix-transfer";

    private final RouteResolver resolver = resolverWith(
            new InterceptRule(RULE_NAME, PROTECTED_PATH, Set.of(HttpMethod.POST), JOURNEY));

    @Nested
    @DisplayName("Rota verificada")
    class Intercepted {

        @Test
        void reconhece_caminho_e_metodo_configurados() {

            RouteDecision decision = resolver.resolve(PROTECTED_PATH, HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.INTERCEPT);
            assertThat(decision.rule().journey()).isEqualTo(JOURNEY);
            assertThat(decision.metricTag()).isEqualTo(RULE_NAME);
        }

        @Test
        @DisplayName("o mesmo caminho com outro método não é verificado")
        void ignora_metodo_fora_da_regra() {

            RouteDecision decision = resolver.resolve(PROTECTED_PATH, HttpMethod.GET);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }

        @Test
        @DisplayName("caminho que apenas começa igual não é verificado")
        void nao_casa_por_prefixo() {

            RouteDecision decision = resolver.resolve(
                    PROTECTED_PATH + "/confirmacao", HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }

        @Test
        @DisplayName("caminho contido no configurado não é verificado")
        void nao_casa_por_sufixo() {

            RouteDecision decision = resolver.resolve("/api/v1/pix", HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }
    }

    @Nested
    @DisplayName("Caminho que a comparação não alcança")
    class Rejected {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "/api/v1/pix/../pix/transferencia",
                "/api/v1/pix/%2e%2e/transferencia",
                "/api/v1//pix/transferencia",
                "/api/v1/pix/transferencia%00"
        })
        void recusa_o_que_pode_significar_outra_coisa_no_destino(String path) {

            RouteDecision decision = resolver.resolve(path, HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.REJECT);
            assertThat(decision.rejectionReason()).isNotBlank();
        }

        @Test
        void recusa_caminho_ausente() {

            assertThat(resolver.resolve(null, HttpMethod.POST).outcome())
                    .isEqualTo(RouteDecision.Outcome.REJECT);

            assertThat(resolver.resolve("  ", HttpMethod.POST).outcome())
                    .isEqualTo(RouteDecision.Outcome.REJECT);
        }

        @Test
        void recusa_metodo_ausente() {

            RouteDecision decision = resolver.resolve(PROTECTED_PATH, null);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.REJECT);
        }

        @Test
        @DisplayName("recusa antes de comparar, mesmo em rota que atravessaria")
        void recusa_tambem_fora_da_matriz() {

            RouteDecision decision = resolver.resolve("/outra/../rota", HttpMethod.GET);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.REJECT);
        }
    }

    @Nested
    @DisplayName("Rota fora da matriz")
    class Passthrough {

        @Test
        void atravessa_o_que_nao_esta_configurado() {

            RouteDecision decision = resolver.resolve("/api/v1/pix/consulta", HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
            assertThat(decision.rule()).isNull();
            assertThat(decision.metricTag()).isEqualTo("sem-regra");
        }

        @Test
        @DisplayName("sem regra configurada, tudo atravessa")
        void atravessa_tudo_quando_a_matriz_esta_vazia() {

            RouteResolver empty = resolverWith();

            assertThat(empty.resolve(PROTECTED_PATH, HttpMethod.POST).outcome())
                    .isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }
    }

    @Nested
    @DisplayName("Várias regras")
    class MultipleRules {

        @Test
        void reconhece_cada_regra_pelo_proprio_caminho() {

            RouteResolver multiple = resolverWith(
                    new InterceptRule("pix", "/api/v1/pix", Set.of(HttpMethod.POST), "j-pix"),
                    new InterceptRule("ted", "/api/v1/ted", Set.of(HttpMethod.POST), "j-ted"));

            assertThat(multiple.resolve("/api/v1/pix", HttpMethod.POST).rule().journey())
                    .isEqualTo("j-pix");

            assertThat(multiple.resolve("/api/v1/ted", HttpMethod.POST).rule().journey())
                    .isEqualTo("j-ted");
        }

        @Test
        void reconhece_mais_de_um_metodo_na_mesma_regra() {

            RouteResolver multiple = resolverWith(new InterceptRule(
                    "pix", "/api/v1/pix", Set.of(HttpMethod.POST, HttpMethod.PUT), "j-pix"));

            assertThat(multiple.resolve("/api/v1/pix", HttpMethod.POST).outcome())
                    .isEqualTo(RouteDecision.Outcome.INTERCEPT);

            assertThat(multiple.resolve("/api/v1/pix", HttpMethod.PUT).outcome())
                    .isEqualTo(RouteDecision.Outcome.INTERCEPT);

            assertThat(multiple.resolve("/api/v1/pix", HttpMethod.DELETE).outcome())
                    .isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }
    }

    private static RouteResolver resolverWith(InterceptRule... rules) {
        return new RouteResolver(new ProxyProperties(
                URI.create("http://localhost:8081"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                2_097_152L,
                List.of(rules),
                "x-correlation-id"));
    }
}