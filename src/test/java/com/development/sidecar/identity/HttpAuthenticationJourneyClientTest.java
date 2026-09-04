package com.development.sidecar.identity;

import com.development.sidecar.config.IdentityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAuthenticationJourneyClientTest {

    private static final String BASE_URL = "https://provedor.invalid/am";
    private static final String AUTHENTICATE = BASE_URL + "/json/realms/alpha/authenticate";

    private static final String JOURNEY = "jornada-transacional";
    private static final String CHANNEL_TOKEN = "fake.token.ydJ";
    private static final String CODE = "fake.codigo.039";
    private static final String AUTH_ID = "fake.sessao.Qm7";
    private static final String TOKEN_ID = "fake.sessao-emitida.Bt4";

    private static final String CHANNEL_TOKEN_HEADER = "x-canal-autenticacao";
    private static final String CODE_HEADER = "x-canal-codigo";

    private static final byte[] PAYLOAD =
            "{\"channel\":{\"message\":\"não identificado\"},\"risk\":{}}"
                    .getBytes(StandardCharsets.UTF_8);

    private static final String PAYLOAD_CALLBACK = """
            {"authId":"%s","callbacks":[{"type":"NameCallback",
            "output":[{"name":"prompt","value":"PAYLOAD_REQUIRED"}],
            "input":[{"name":"IDToken1","value":"PAYLOAD_REQUIRED"}]}]}""".formatted(AUTH_ID);

    private static final String CHALLENGE_CALLBACK = """
            {"authId":"%s","callbacks":[{"type":"NameCallback",
            "output":[{"name":"prompt","value":"CHALLENGE_REQUIRED"}],
            "input":[{"name":"IDToken1","value":"OTP:AUTHFY"}]}]}""".formatted(AUTH_ID);

    private static final String POLLING_CALLBACK = """
            {"authId":"%s","callbacks":[{"type":"PollingWaitCallback",
            "output":[{"name":"waitTime","value":"8000"},
            {"name":"message","value":"app://exemplo/desafio"}]}]}""".formatted(AUTH_ID);

    private static final String COMPLETED = """
            {"tokenId":"%s","successUrl":"/enduser/","realm":"/alpha"}""".formatted(TOKEN_ID);

    private MockRestServiceServer server;
    private AuthenticationJourneyClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);

        server = MockRestServiceServer.bindTo(builder).build();

        client = new HttpAuthenticationJourneyClient(builder.build(), properties());
    }

    @Nested
    @DisplayName("Início da jornada")
    class Start {

        @Test
        @DisplayName("apresenta o token do canal e o código do autenticador")
        void apresenta_as_credenciais() {

            server.expect(requestTo(authenticateUri()))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header(CHANNEL_TOKEN_HEADER, CHANNEL_TOKEN))
                    .andExpect(header(CODE_HEADER, CODE))
                    .andExpect(header("Accept-API-Version", "resource=2.1"))
                    .andRespond(withSuccess(CHALLENGE_CALLBACK, MediaType.APPLICATION_JSON));

            client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            server.verify();
        }

        @Test
        @DisplayName("sem código, a jornada decide o que fazer")
        void omite_o_codigo_quando_nao_ha() {

            server.expect(requestTo(authenticateUri()))
                    .andExpect(header(CHANNEL_TOKEN_HEADER, CHANNEL_TOKEN))
                    .andExpect(request -> assertThat(request.getHeaders().get(CODE_HEADER))
                            .isNull())
                    .andRespond(withSuccess(CHALLENGE_CALLBACK, MediaType.APPLICATION_JSON));

            client.start(JOURNEY, CHANNEL_TOKEN, null, PAYLOAD);

            server.verify();
        }

        @Test
        @DisplayName("sem corpo, a jornada que não o pede segue normalmente")
        void aceita_sem_corpo() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess(POLLING_CALLBACK, MediaType.APPLICATION_JSON));

            JourneyOutcome outcome = client.start(JOURNEY, CHANNEL_TOKEN, CODE, new byte[0]);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.CHALLENGE);
            server.verify();
        }

        @Test
        @DisplayName("a jornada que pede o corpo recusa quando não há corpo")
        void recusa_quando_o_corpo_e_pedido_e_nao_ha() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess(PAYLOAD_CALLBACK, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.start(JOURNEY, CHANNEL_TOKEN, CODE, new byte[0]))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }

        @Test
        void recusa_sem_jornada() {

            assertThatThrownBy(() -> client.start("  ", CHANNEL_TOKEN, CODE, PAYLOAD))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }

        @Test
        void recusa_sem_token_do_canal() {

            assertThatThrownBy(() -> client.start(JOURNEY, null, CODE, PAYLOAD))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("Apresentação do corpo")
    class PayloadSubmission {

        @Test
        @DisplayName("quando a jornada pede o corpo, ele é apresentado no mesmo passo")
        void encadeia_a_apresentacao_do_corpo() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess(PAYLOAD_CALLBACK, MediaType.APPLICATION_JSON));

            server.expect(requestTo(AUTHENTICATE))
                    .andExpect(jsonPath("$.authId").value(AUTH_ID))
                    .andExpect(jsonPath("$.callbacks[0].input[0].value")
                            .value(new String(PAYLOAD, StandardCharsets.UTF_8)))
                    .andRespond(withSuccess(COMPLETED, MediaType.APPLICATION_JSON));

            JourneyOutcome outcome = client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);
            server.verify();
        }

        @Test
        @DisplayName("o corpo é apresentado como veio, sem reserializar")
        void nao_reserializa_o_corpo() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess(PAYLOAD_CALLBACK, MediaType.APPLICATION_JSON));

            server.expect(requestTo(AUTHENTICATE))
                    .andExpect(jsonPath("$.callbacks[0].input[0].value")
                            .value("{\"channel\":{\"message\":\"não identificado\"},\"risk\":{}}"))
                    .andRespond(withSuccess(COMPLETED, MediaType.APPLICATION_JSON));

            client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            server.verify();
        }

        @Test
        @DisplayName("o callback volta como o provedor o emitiu")
        void devolve_o_callback_completo() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess(PAYLOAD_CALLBACK, MediaType.APPLICATION_JSON));

            server.expect(requestTo(AUTHENTICATE))
                    .andExpect(jsonPath("$.callbacks[0].type").value("NameCallback"))
                    .andExpect(jsonPath("$.callbacks[0].output[0].value")
                            .value("PAYLOAD_REQUIRED"))
                    .andRespond(withSuccess(COMPLETED, MediaType.APPLICATION_JSON));

            client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            server.verify();
        }

        @Test
        @DisplayName("desafio que não pede corpo é devolvido ao chamador")
        void nao_encadeia_outro_desafio() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess(CHALLENGE_CALLBACK, MediaType.APPLICATION_JSON));

            JourneyOutcome outcome = client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.CHALLENGE);
            server.verify();
        }
    }

    @Nested
    @DisplayName("Continuação")
    class Advance {

        @Test
        void apresenta_a_resposta_na_entrada() {

            server.expect(requestTo(AUTHENTICATE))
                    .andExpect(jsonPath("$.authId").value(AUTH_ID))
                    .andExpect(jsonPath("$.callbacks[0].input[0].value").value(CODE))
                    .andRespond(withSuccess(COMPLETED, MediaType.APPLICATION_JSON));

            JourneyOutcome outcome = client.advance(AUTH_ID, CODE);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);
            server.verify();
        }

        @Test
        @DisplayName("sem resposta, apresenta a sessão sem callback: é como se pergunta")
        void consulta_o_desfecho_sem_callback() {

            server.expect(requestTo(AUTHENTICATE))
                    .andExpect(jsonPath("$.authId").value(AUTH_ID))
                    .andExpect(jsonPath("$.callbacks").isEmpty())
                    .andRespond(withSuccess(COMPLETED, MediaType.APPLICATION_JSON));

            JourneyOutcome outcome = client.advance(AUTH_ID, null);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);
            server.verify();
        }

        @Test
        void recusa_sem_identificador_de_jornada() {

            assertThatThrownBy(() -> client.advance("  ", CODE))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("Tradução da resposta")
    class Translation {

        @Test
        @DisplayName("extrai o código da recusa, não a mensagem")
        void recusa_traz_o_codigo() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"code":401,"message":"OTP nulo (Journey encerrada)",
                                    "detail":{"errorCode":"002"}}"""));

            JourneyOutcome outcome = client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
            assertThat(outcome.reason()).isEqualTo("002");
        }

        @Test
        void sessao_vencida_vira_expiracao() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withStatus(HttpStatus.REQUEST_TIMEOUT));

            JourneyOutcome outcome = client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.EXPIRED);
        }

        @Test
        @DisplayName("chamada recusada é indisponibilidade: jornada ou realm mal configurados")
        void chamada_recusada_vira_indisponibilidade() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST));

            assertThatThrownBy(() -> client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }

        @Test
        @DisplayName("status inesperado é indisponibilidade, não recusa")
        void status_inesperado_vira_indisponibilidade() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatThrownBy(() -> client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }

        @Test
        void resposta_sem_desfecho_vira_recusa() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

            JourneyOutcome outcome = client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
        }

        @Test
        void corpo_vazio_vira_indisponibilidade() {

            server.expect(requestTo(authenticateUri()))
                    .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }
    }

    private static URI authenticateUri() {
        return URI.create(AUTHENTICATE
                + "?authIndexType=service&authIndexValue=" + JOURNEY);
    }

    private static IdentityProperties properties() {
        return new IdentityProperties(
                URI.create(BASE_URL),
                "alpha",
                "service",
                "cliente",
                "segredo",
                "https://localhost",
                "openid",
                "cookie",
                CHANNEL_TOKEN_HEADER,
                CODE_HEADER,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10));
    }
}