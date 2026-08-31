package com.development.sidecar.proxy;

import com.development.sidecar.config.ChannelProperties;
import com.development.sidecar.config.IdentityProperties;
import com.development.sidecar.config.ProxyProperties;
import com.development.sidecar.config.ProxyProperties.InterceptRule;
import com.development.sidecar.contract.ChallengeAnswerReader;
import com.development.sidecar.contract.ChannelResponseWriter;
import com.development.sidecar.identity.AccessToken;
import com.development.sidecar.identity.AuthorizationOrchestrator;
import com.development.sidecar.identity.AuthorizationResult;
import com.development.sidecar.identity.JourneyStep;
import com.development.sidecar.identity.RefusalKind;
import com.development.sidecar.identity.TokenReference;
import com.development.sidecar.observability.SidecarMetrics;
import com.development.sidecar.route.RouteResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProxyFilterTest {

    private static final String PROTECTED_PATH = "/api/v1/pix/transferencia";
    private static final String OPEN_PATH = "/api/v1/pix/consulta";
    private static final String JOURNEY = "jornada-transacional";

    private static final String CHANNEL_TOKEN_HEADER = "x-canal-autenticacao";
    private static final String CODE_HEADER = "x-canal-codigo";
    private static final String SESSION_HEADER = "x-authz-session";
    private static final String TOKEN_REF_HEADER = "x-authz-token-ref";
    private static final String CORRELATION_HEADER = "x-correlation-id";

    private static final String TOKEN_REF = "84da0844-d1f9-31f9-b4f4-79b420be8be4";
    private static final String ACCESS_TOKEN = "opaco-do-provedor";
    private static final String BODY = "{\"channel\":{},\"risk\":{}}";

    @Mock
    private RequestForwarder forwarder;

    @Mock
    private AuthorizationOrchestrator orchestrator;

    private ProxyFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        ProxyProperties properties = proxyProperties();

        filter = new ProxyFilter(
                new RouteResolver(properties),
                forwarder,
                orchestrator,
                new ChannelResponseWriter(JsonMapper.builder().build(), CORRELATION_HEADER),
                new ChallengeAnswerReader(JsonMapper.builder().build()),
                new SidecarMetrics(new SimpleMeterRegistry()),
                new ChannelProperties(SESSION_HEADER, CODE_HEADER, TOKEN_REF_HEADER),
                identityProperties(),
                properties);

        response = new MockHttpServletResponse();
    }

    @Nested
    @DisplayName("Escolha do caminho")
    class Routing {

        @Test
        @DisplayName("rota fora da matriz atravessa sem autorização")
        void atravessa_rota_aberta() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());

            filter.doFilter(request(OPEN_PATH), response, new MockFilterChain());

            verifyNoInteractions(orchestrator);
            verify(forwarder).forward(any(), any(), eq(Map.of()), eq(null));
        }

        @Test
        @DisplayName("sem cabeçalho de autorização, a jornada começa")
        void inicia_a_jornada() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any())).thenReturn(BODY.getBytes(StandardCharsets.UTF_8));
            when(orchestrator.start(anyString(), any(), any(), any(), anyString()))
                    .thenReturn(AuthorizationResult.authorized(new TokenReference(TOKEN_REF)));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            verify(orchestrator).start(eq(JOURNEY), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("com a sessão, a jornada continua")
        void continua_a_jornada() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any()))
                    .thenReturn("{\"authz\":{\"response\":\"149707\"}}"
                            .getBytes(StandardCharsets.UTF_8));
            when(orchestrator.advance(anyString(), anyString(), anyString()))
                    .thenReturn(AuthorizationResult.authorized(new TokenReference(TOKEN_REF)));

            MockHttpServletRequest request = request(PROTECTED_PATH);
            request.addHeader(SESSION_HEADER, "sessao");

            filter.doFilter(request, response, new MockFilterChain());

            verify(orchestrator).advance(eq("sessao"), eq("149707"), anyString());
        }

        @Test
        @DisplayName("com a referência, a transação é efetivada")
        void efetiva_a_transacao() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(orchestrator.resolve(anyString(), anyString()))
                    .thenReturn(AuthorizationResult.resolved(accessToken()));

            MockHttpServletRequest request = request(PROTECTED_PATH);
            request.addHeader(TOKEN_REF_HEADER, TOKEN_REF);

            filter.doFilter(request, response, new MockFilterChain());

            verify(orchestrator).resolve(eq(TOKEN_REF), anyString());
        }

        @Test
        @DisplayName("a referência tem precedência sobre a sessão")
        void referencia_vence_sessao() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(orchestrator.resolve(anyString(), anyString()))
                    .thenReturn(AuthorizationResult.resolved(accessToken()));

            MockHttpServletRequest request = request(PROTECTED_PATH);
            request.addHeader(TOKEN_REF_HEADER, TOKEN_REF);
            request.addHeader(SESSION_HEADER, "sessao");

            filter.doFilter(request, response, new MockFilterChain());

            verify(orchestrator).resolve(anyString(), anyString());
            verify(orchestrator, never()).advance(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Substituição da referência pelo token")
    class TokenInjection {

        @Test
        @DisplayName("o serviço de negócio recebe o token, não a referência")
        void escreve_o_token_no_encaminhamento() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(orchestrator.resolve(anyString(), anyString()))
                    .thenReturn(AuthorizationResult.resolved(accessToken()));

            MockHttpServletRequest request = request(PROTECTED_PATH);
            request.addHeader(TOKEN_REF_HEADER, TOKEN_REF);

            filter.doFilter(request, response, new MockFilterChain());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> injected =
                    ArgumentCaptor.forClass(Map.class);

            verify(forwarder).forward(any(), any(), injected.capture(), any());

            assertThat(injected.getValue())
                    .containsEntry(TOKEN_REF_HEADER, ACCESS_TOKEN)
                    .doesNotContainValue(TOKEN_REF);
        }
    }

    @Nested
    @DisplayName("Respostas ao canal")
    class Responses {

        @Test
        void desafio_vira_precondicao_requerida() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any())).thenReturn(BODY.getBytes(StandardCharsets.UTF_8));
            when(orchestrator.start(anyString(), any(), any(), any(), anyString()))
                    .thenReturn(AuthorizationResult.challenge(challengeStep()));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(428);
            assertThat(response.getHeader("x-authz-required")).isEqualTo("true");
            assertThat(response.getContentAsString()).contains("\"type\":\"OTP\"");
        }

        @Test
        void autorizacao_devolve_a_referencia() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any())).thenReturn(BODY.getBytes(StandardCharsets.UTF_8));
            when(orchestrator.start(anyString(), any(), any(), any(), anyString()))
                    .thenReturn(AuthorizationResult.authorized(new TokenReference(TOKEN_REF)));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains(TOKEN_REF);
        }

        @Test
        @DisplayName("recusa sobre a resposta: um código novo pode resolver")
        void recusa_por_codigo_vira_proibido() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any())).thenReturn(BODY.getBytes(StandardCharsets.UTF_8));
            when(orchestrator.start(anyString(), any(), any(), any(), anyString()))
                    .thenReturn(AuthorizationResult.denied(RefusalKind.RETRY));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("denied");
        }

        @Test
        @DisplayName("recusa sobre o corpo: repetir não muda nada")
        void recusa_por_corpo_vira_requisicao_invalida() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any())).thenReturn(BODY.getBytes(StandardCharsets.UTF_8));
            when(orchestrator.start(anyString(), any(), any(), any(), anyString()))
                    .thenReturn(AuthorizationResult.denied(RefusalKind.INVALID_REQUEST));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getContentAsString()).contains("invalid_request");
        }

        @Test
        void indisponibilidade_vira_servico_indisponivel() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());
            when(forwarder.readBody(any())).thenReturn(BODY.getBytes(StandardCharsets.UTF_8));
            when(orchestrator.start(anyString(), any(), any(), any(), anyString()))
                    .thenReturn(AuthorizationResult.unavailable());

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(503);
        }
    }

    @Nested
    @DisplayName("Recusas antes da autorização")
    class EarlyRejection {

        @Test
        @DisplayName("delimitação ambígua é recusada antes de qualquer verificação")
        void recusa_delimitacao_ambigua() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(
                    java.util.Optional.of(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(400);
            verifyNoInteractions(orchestrator);
        }

        @Test
        void recusa_corpo_acima_do_teto() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(
                    java.util.Optional.of(RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE));

            filter.doFilter(request(PROTECTED_PATH), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(413);
        }

        @Test
        void recusa_caminho_que_a_comparacao_nao_alcanca() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());

            filter.doFilter(request("/api/v1/../pix"), response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(400);
            verifyNoInteractions(orchestrator);
        }
    }

    @Nested
    @DisplayName("Identificador de correlação")
    class Correlation {

        @Test
        void aproveita_o_recebido() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());

            MockHttpServletRequest request = request(OPEN_PATH);
            request.addHeader(CORRELATION_HEADER, "correlacao-do-canal");

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(CORRELATION_HEADER))
                    .isEqualTo("correlacao-do-canal");
        }

        @Test
        void gera_quando_nao_ha() throws Exception {

            when(forwarder.framingRejection(any())).thenReturn(java.util.Optional.empty());

            filter.doFilter(request(OPEN_PATH), response, new MockFilterChain());

            assertThat(response.getHeader(CORRELATION_HEADER)).isNotBlank();
        }
    }

    private static MockHttpServletRequest request(String path) {

        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));

        return request;
    }

    private static JourneyStep challengeStep() {
        return new JourneyStep("sessao", List.of(Map.of(
                "type", "NameCallback",
                "output", List.of(Map.of("name", "prompt", "value", "CHALLENGE_REQUIRED")),
                "input", List.of(Map.of("name", "IDToken1", "value", "OTP:AUTHFY")))), null);
    }

    private static AccessToken accessToken() {
        return new AccessToken(ACCESS_TOKEN, "Bearer", Duration.ofSeconds(3599), "write");
    }

    private static ProxyProperties proxyProperties() {
        return new ProxyProperties(
                URI.create("http://localhost:8081"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                2_097_152L,
                List.of(new InterceptRule("pix", PROTECTED_PATH, Set.of(HttpMethod.POST), JOURNEY)),
                CORRELATION_HEADER);
    }

    private static IdentityProperties identityProperties() {
        return new IdentityProperties(
                URI.create("https://provedor.invalid/am"),
                "alpha", "service", "cliente", "segredo", "https://localhost",
                "openid", "cookie", CHANNEL_TOKEN_HEADER, CODE_HEADER,
                Duration.ofSeconds(2), Duration.ofSeconds(10));
    }
}