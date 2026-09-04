package com.development.sidecar.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyHeaderPolicyTest {

    private static final String RESERVED = "x-empresa-authentication-am";
    private static final String ORDINARY = "content-type";

    private final ProxyHeaderPolicy policy = new ProxyHeaderPolicy(List.of(RESERVED));

    @Nested
    @DisplayName("Cabeçalhos da conexão")
    class HopByHop {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Connection",
                "Keep-Alive",
                "Proxy-Authenticate",
                "Proxy-Authorization",
                "TE",
                "Trailer",
                "Transfer-Encoding",
                "Upgrade"
        })
        @DisplayName("não atravessam: valem para o salto, não para a mensagem")
        void nao_atravessam(String name) {
            assertThat(policy.isForwardable(name, Set.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("Cabeçalhos reconstruídos")
    class Rebuilt {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Host",
                "Content-Length",
                "X-Forwarded-For",
                "X-Forwarded-Proto",
                "X-Forwarded-Host"
        })
        @DisplayName("não são copiados: o encaminhador os escreve")
        void nao_sao_copiados(String name) {
            assertThat(policy.isForwardable(name, Set.of())).isFalse();
        }

        @Test
        @DisplayName("copiar a cadeia de encaminhamento produziria dois valores")
        void a_cadeia_de_encaminhamento_nunca_e_copiada() {
            assertThat(policy.isForwardable("x-forwarded-for", Set.of())).isFalse();
        }
    }

    @Nested
    @DisplayName("Cabeçalhos reservados")
    class Reserved {

        @Test
        void nao_atravessam_o_que_o_chamador_declarou() {
            assertThat(policy.isForwardable(RESERVED, Set.of())).isFalse();
        }

        @Test
        void sao_reconhecidos_como_reservados() {
            assertThat(policy.isReserved(RESERVED)).isTrue();
            assertThat(policy.isReserved(ORDINARY)).isFalse();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "X-empresa-Authentication-AM",
                "x-empresa-authentication-am",
                "X-empresa-AUTHENTICATION-AM",
                "  x-empresa-authentication-am  "
        })
        @DisplayName("o nome é comparado sem depender de caixa ou espaço")
        void reconhece_qualquer_grafia(String name) {
            assertThat(policy.isReserved(name)).isTrue();
        }

        @Test
        @DisplayName("sem reservados configurados, nada é reservado")
        void lista_vazia_nao_reserva_nada() {

            ProxyHeaderPolicy empty = new ProxyHeaderPolicy(List.of());

            assertThat(empty.isReserved(RESERVED)).isFalse();
            assertThat(empty.isForwardable(RESERVED, Set.of())).isTrue();
        }

        @Test
        void tolera_lista_nula_e_entradas_vazias() {

            assertThat(new ProxyHeaderPolicy(null).isReserved(RESERVED)).isFalse();

            ProxyHeaderPolicy sparse = new ProxyHeaderPolicy(
                    Arrays.asList(RESERVED, null, "", "  "));

            assertThat(sparse.isReserved(RESERVED)).isTrue();
        }
    }

    @Nested
    @DisplayName("Cabeçalhos declarados pela própria mensagem")
    class ConnectionTokens {

        @Test
        @DisplayName("o que a conexão declara como seu não atravessa")
        void respeita_o_que_a_mensagem_declara() {

            Set<String> tokens = ProxyHeaderPolicy.connectionTokens("X-Custom-Header");

            assertThat(policy.isForwardable("x-custom-header", tokens)).isFalse();
            assertThat(policy.isForwardable("x-outro", tokens)).isTrue();
        }

        @Test
        void separa_varios_nomes_na_mesma_declaracao() {

            Set<String> tokens = ProxyHeaderPolicy.connectionTokens("Foo, Bar , Baz");

            assertThat(tokens).containsExactlyInAnyOrder("foo", "bar", "baz");
        }

        @Test
        void tolera_declaracao_ausente_ou_vazia() {

            assertThat(ProxyHeaderPolicy.connectionTokens()).isEmpty();
            assertThat(ProxyHeaderPolicy.connectionTokens((String[]) null)).isEmpty();
            assertThat(ProxyHeaderPolicy.connectionTokens("", "  ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Cabeçalhos comuns")
    class Ordinary {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "Content-Type",
                "Accept",
                "User-Agent",
                "x-empresa-authentication",
                "x-empresa-correlation-id"
        })
        void atravessam(String name) {
            assertThat(policy.isForwardable(name, Set.of())).isTrue();
        }

        @Test
        void nome_ausente_nao_atravessa() {

            assertThat(policy.isForwardable(null, Set.of())).isFalse();
            assertThat(policy.isForwardable("", Set.of())).isFalse();
            assertThat(policy.isForwardable("   ", Set.of())).isFalse();
        }
    }
}