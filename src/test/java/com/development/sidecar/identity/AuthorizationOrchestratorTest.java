package com.development.sidecar.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationOrchestratorTest {

    private static final String JOURNEY = "jornada-transacional";
    private static final String CHANNEL_TOKEN = "eyJraWQiOi...";
    private static final String CODE = "149707";
    private static final String RULE = "pix-transfer";
    private static final String SESSION = "eyJ0eXAiOiJKV1Qi...";
    private static final String TOKEN_REF = "84da0844-d1f9-31f9-b4f4-79b420be8be4";

    private static final byte[] PAYLOAD =
            "{\"channel\":{},\"risk\":{}}".getBytes(StandardCharsets.UTF_8);

    @Mock
    private AuthenticationJourneyClient journeyClient;

    @Mock
    private TokenIssuer tokenIssuer;

    @Mock
    private TokenCustodian tokenCustodian;

    @InjectMocks
    private AuthorizationOrchestrator orchestrator;

    @Nested
    @DisplayName("Início da autorização")
    class Start {

        @Test
        @DisplayName("apresenta o corpo ao provedor como recebeu")
        void apresenta_o_corpo_recebido() {

            when(journeyClient.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD))
                    .thenReturn(JourneyOutcome.challenge(challengeStep()));

            orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            verify(journeyClient).start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD);
        }

        @Test
        @DisplayName("sem o token do canal, não chega a chamar o provedor")
        void recusa_sem_token_do_canal() {

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, null, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.SESSION_REQUIRED);
            verifyNoInteractions(journeyClient);
        }

        @Test
        @DisplayName("rota sem jornada é configuração incompleta, não recusa de autorização")
        void recusa_sem_jornada_configurada() {

            AuthorizationResult result =
                    orchestrator.start("  ", CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
            verifyNoInteractions(journeyClient);
        }

        @Test
        @DisplayName("sem corpo não há o que autorizar")
        void recusa_sem_corpo() {

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, new byte[0], RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.BAD_REQUEST);
            verifyNoInteractions(journeyClient);
        }

        @Test
        @DisplayName("provedor fora do ar é indisponibilidade, não recusa")
        void traduz_provedor_indisponivel() {

            when(journeyClient.start(anyString(), anyString(), anyString(), any()))
                    .thenThrow(new AuthenticationJourneyClient.JourneyUnavailableException("x"));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("Resposta ao desafio")
    class Advance {

        @Test
        void apresenta_a_resposta_ao_provedor() {

            when(journeyClient.advance(SESSION, CODE))
                    .thenReturn(JourneyOutcome.challenge(challengeStep()));

            orchestrator.advance(SESSION, CODE, RULE);

            verify(journeyClient).advance(SESSION, CODE);
        }

        @Test
        @DisplayName("sem resposta, não chega a chamar o provedor")
        void recusa_sem_resposta() {

            AuthorizationResult result = orchestrator.advance(SESSION, "  ", RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.BAD_REQUEST);
            verifyNoInteractions(journeyClient);
        }
    }

    @Nested
    @DisplayName("Desfecho da jornada")
    class Outcomes {

        @Test
        void desafio_vira_desafio_ao_canal() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.challenge(challengeStep()));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.CHALLENGE);
            assertThat(result.step()).isNotNull();
        }

        @Test
        @DisplayName("desafio sem passo é resposta inutilizável")
        void desafio_sem_passo_vira_indisponibilidade() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(new JourneyOutcome(JourneyOutcome.Type.CHALLENGE, null, null));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
        }

        @Test
        @DisplayName("a recusa carrega o que ela significa para o canal")
        void recusa_carrega_a_classificacao() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.denied("014"));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
            assertThat(result.refusal()).isEqualTo(RefusalKind.INVALID_REQUEST);
        }

        @Test
        void sessao_vencida_vira_expiracao() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.expired());

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.EXPIRED);
        }
    }

    @Nested
    @DisplayName("Emissão e guarda do token")
    class Issuance {

        @Test
        @DisplayName("jornada concluída vira referência ao canal")
        void emite_e_guarda() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.completed(completedStep()));

            AccessToken token = accessToken();

            when(tokenIssuer.issue(SESSION)).thenReturn(token);
            when(tokenCustodian.store(token)).thenReturn(new TokenReference(TOKEN_REF));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.AUTHORIZED);
            assertThat(result.reference().tokenRef()).isEqualTo(TOKEN_REF);
        }

        @Test
        @DisplayName("conclusão sem sessão emitida não permite seguir")
        void recusa_conclusao_sem_sessao() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.completed(new JourneyStep(null, List.of(), "  ")));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
            verifyNoInteractions(tokenIssuer);
        }

        @Test
        @DisplayName("falha na emissão é indisponibilidade: o titular já provou quem é")
        void falha_na_emissao_nao_e_recusa() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.completed(completedStep()));

            when(tokenIssuer.issue(SESSION))
                    .thenThrow(new TokenIssuer.TokenIssuanceException("x"));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
            verify(tokenCustodian, never()).store(any());
        }

        @Test
        void falha_na_guarda_e_indisponibilidade() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.completed(completedStep()));

            when(tokenIssuer.issue(SESSION)).thenReturn(accessToken());
            when(tokenCustodian.store(any()))
                    .thenThrow(new TokenCustodian.TokenCustodyException("x"));

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("Efetivação")
    class Resolve {

        @Test
        void devolve_o_token_guardado() {

            AccessToken token = accessToken();

            when(tokenCustodian.retrieve(TOKEN_REF)).thenReturn(token);

            AuthorizationResult result = orchestrator.resolve(TOKEN_REF, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.RESOLVED);
            assertThat(result.token()).isEqualTo(token);
        }

        @Test
        @DisplayName("referência desconhecida pede nova autorização")
        void referencia_desconhecida() {

            when(tokenCustodian.retrieve(TOKEN_REF))
                    .thenThrow(new TokenCustodian.TokenNotFoundException("x"));

            AuthorizationResult result = orchestrator.resolve(TOKEN_REF, RULE);

            assertThat(result.type())
                    .isEqualTo(AuthorizationResult.Type.AUTHORIZATION_REQUIRED);
        }

        @Test
        @DisplayName("guardião fora do ar não invalida a referência")
        void guardiao_indisponivel() {

            when(tokenCustodian.retrieve(TOKEN_REF))
                    .thenThrow(new TokenCustodian.TokenCustodyException("x"));

            AuthorizationResult result = orchestrator.resolve(TOKEN_REF, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
        }
    }

    private static JourneyStep challengeStep() {
        return new JourneyStep(SESSION, List.of(Map.of("type", "NameCallback")), null);
    }

    private static JourneyStep completedStep() {
        return new JourneyStep(null, List.of(), SESSION);
    }

    private static AccessToken accessToken() {
        return new AccessToken("opaco", "Bearer", Duration.ofSeconds(3599), "write");
    }
}