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
    private static final String CHANNEL_TOKEN = "fake.token.ydJ";
    private static final String CODE = "fake.codigo.039";
    private static final String RULE = "pix-transfer";
    private static final String SESSION = "fake.sessao.Qm7";
    private static final String TOKEN_REF = "fake.referencia.Lp2";

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
    @DisplayName("Continuação da jornada")
    class Advance {

        @Test
        void apresenta_a_resposta_ao_provedor() {

            when(journeyClient.advance(SESSION, CODE))
                    .thenReturn(JourneyOutcome.challenge(challengeStep()));

            orchestrator.advance(SESSION, CODE, RULE);

            verify(journeyClient).advance(SESSION, CODE);
        }

        @Test
        @DisplayName("sem resposta, consulta o desfecho: há desafios cumpridos fora do canal")
        void sem_resposta_consulta_o_desfecho() {

            when(journeyClient.advance(SESSION, null))
                    .thenReturn(JourneyOutcome.challenge(challengeStep()));

            AuthorizationResult result = orchestrator.advance(SESSION, null, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.CHALLENGE);
            verify(journeyClient).advance(SESSION, null);
        }

        @Test
        @DisplayName("quem decide se a ausência de resposta serve é o provedor")
        void ausencia_de_resposta_e_decidida_pelo_provedor() {

            when(journeyClient.advance(SESSION, null))
                    .thenReturn(JourneyOutcome.denied("002"));

            AuthorizationResult result = orchestrator.advance(SESSION, null, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
            assertThat(result.refusal()).isEqualTo(RefusalKind.CODE_REQUIRED);
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
        void sessao_vencida_vira_expiracao() {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.expired());

            AuthorizationResult result =
                    orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.EXPIRED);
        }
    }

    @Nested
    @DisplayName("Nem tudo que o provedor recusa é recusa de autorização")
    class RefusalTranslation {

        @Test
        @DisplayName("sessão do canal recusada pede renovação, não código novo")
        void sessao_do_canal_recusada() {

            AuthorizationResult result = denied("001");

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.EXPIRED);
        }

        @Test
        @DisplayName("falha do provedor é indisponibilidade: o titular não tem o que corrigir")
        void falha_do_provedor() {

            AuthorizationResult result = denied("006");

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.UNAVAILABLE);
        }

        @Test
        @DisplayName("código não informado")
        void codigo_nao_informado() {

            AuthorizationResult result = denied("002");

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
            assertThat(result.refusal()).isEqualTo(RefusalKind.CODE_REQUIRED);
        }

        @Test
        @DisplayName("código incorreto")
        void codigo_incorreto() {

            AuthorizationResult result = denied("003");

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
            assertThat(result.refusal()).isEqualTo(RefusalKind.CODE_INVALID);
        }

        @Test
        @DisplayName("corpo fora do contrato")
        void corpo_fora_do_contrato() {

            AuthorizationResult result = denied("014");

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
            assertThat(result.refusal()).isEqualTo(RefusalKind.PAYLOAD_INVALID);
        }

        @Test
        @DisplayName("código não mapeado vira recusa genérica")
        void codigo_desconhecido() {

            AuthorizationResult result = denied("007");

            assertThat(result.type()).isEqualTo(AuthorizationResult.Type.DENIED);
            assertThat(result.refusal()).isEqualTo(RefusalKind.DENIED);
        }

        private AuthorizationResult denied(String code) {

            when(journeyClient.start(anyString(), anyString(), any(), any()))
                    .thenReturn(JourneyOutcome.denied(code));

            return orchestrator.start(JOURNEY, CHANNEL_TOKEN, CODE, PAYLOAD, RULE);
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
        return new AccessToken("fake.acesso.Wq5", "Bearer", Duration.ofSeconds(3599), "write");
    }
}