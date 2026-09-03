package com.development.sidecar.contract;

import com.development.sidecar.identity.JourneyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeMapperTest {

    private static final String AUTH_ID = "fake.sessao.Qm7";
    private static final String DEEPLINK = "app://exemplo/empresa/pdc?authIndexValue=abc123";

    @Nested
    @DisplayName("Desafio que o canal cumpre")
    class ChallengeType {

        @Test
        @DisplayName("separa tipo e provedor quando o provedor os une")
        void separa_tipo_e_provedor() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY")));

            assertThat(response.challenge().type()).isEqualTo("OTP");
            assertThat(response.challenge().provider()).isEqualTo("AUTHFY");
        }

        @Test
        void reconhece_desafio_de_biometria() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "BIOMETRIA:UNICO")));

            assertThat(response.challenge().type()).isEqualTo("BIOMETRIA");
            assertThat(response.challenge().provider()).isEqualTo("UNICO");
        }

        @Test
        @DisplayName("sem separador, o valor inteiro é o tipo e não há provedor")
        void aceita_valor_sem_provedor() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP")));

            assertThat(response.challenge().type()).isEqualTo("OTP");
            assertThat(response.challenge().provider()).isNull();
        }

        @Test
        @DisplayName("prefere o valor sugerido ao rótulo: é ele que nomeia o desafio")
        void prefere_a_entrada_ao_rotulo() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY")));

            assertThat(response.challenge().type()).isEqualTo("OTP");
        }

        @Test
        @DisplayName("sem valor sugerido, recorre ao rótulo")
        void recorre_ao_rotulo_quando_a_entrada_esta_vazia() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("PAYLOAD_REQUIRED", "")));

            assertThat(response.challenge().type()).isEqualTo("PAYLOAD_REQUIRED");
        }

        @Test
        @DisplayName("com mais de um callback, o primeiro é o que nomeia o desafio")
        void varios_callbacks() {

            JourneyStep step = new JourneyStep(AUTH_ID, List.of(
                    nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY"),
                    nameCallback("OTHER", "X:Y")), null);

            ChallengeResponse response = ChallengeMapper.toChallenge(step);

            assertThat(response.challenge().type()).isEqualTo("OTP");
        }

        @Test
        @DisplayName("não traz endereço nem espera: o canal cumpre e responde")
        void nao_traz_endereco_nem_espera() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY")));

            assertThat(response.challenge().target()).isNull();
            assertThat(response.challenge().retryAfter()).isNull();
        }
    }

    @Nested
    @DisplayName("Desafio cumprido fora do canal")
    class Handoff {

        @Test
        @DisplayName("o callback de espera vira endereço a abrir e tempo a aguardar")
        void traduz_a_espera() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(pollingCallback("8000", DEEPLINK)));

            assertThat(response.challenge().type()).isEqualTo("DEEPLINK");
            assertThat(response.challenge().target()).isEqualTo(DEEPLINK);
            assertThat(response.challenge().retryAfter()).isEqualTo(8000L);
        }

        @Test
        @DisplayName("não traz provedor: não há com quem cumprir, e sim onde")
        void nao_traz_provedor() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(pollingCallback("8000", DEEPLINK)));

            assertThat(response.challenge().provider()).isNull();
        }

        @Test
        @DisplayName("o endereço atravessa como o provedor o escreveu")
        void preserva_o_endereco() {

            String withQuery = "app://empresa/pdc?realm=%2Falpha&authIndexType=transaction";

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(pollingCallback("5000", withQuery)));

            assertThat(response.challenge().target()).isEqualTo(withQuery);
        }

        @Test
        @DisplayName("espera ilegível não atravessa")
        void espera_ilegivel() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(pollingCallback("oito mil", DEEPLINK)));

            assertThat(response.challenge().type()).isEqualTo("DEEPLINK");
            assertThat(response.challenge().retryAfter()).isNull();
        }

        @Test
        @DisplayName("endereço com caractere de controle não atravessa")
        void endereco_com_caractere_de_controle() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(pollingCallback("8000", "app://exemplo\ninjecao")));

            assertThat(response.challenge().target()).isNull();
        }

        @Test
        @DisplayName("a espera é reconhecida mesmo sem mensagem")
        void espera_sem_endereco() {

            Map<String, Object> polling = Map.of(
                    "type", "PollingWaitCallback",
                    "output", List.of(Map.of("name", "waitTime", "value", "8000")));

            ChallengeResponse response = ChallengeMapper.toChallenge(stepWith(polling));

            assertThat(response.challenge().type()).isEqualTo("DEEPLINK");
            assertThat(response.challenge().target()).isNull();
            assertThat(response.challenge().retryAfter()).isEqualTo(8000L);
        }
    }

    @Nested
    @DisplayName("Estruturas que não nomeiam o desafio")
    class Unrecognized {

        @Test
        void sem_callback_algum() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    new JourneyStep(AUTH_ID, List.of(), null));

            assertThat(response.challenge().type()).isEqualTo("UNKNOWN");
        }

        @Test
        void callback_sem_entrada_nem_rotulo() {

            Map<String, Object> callback = Map.of("type", "NameCallback");

            ChallengeResponse response = ChallengeMapper.toChallenge(stepWith(callback));

            assertThat(response.challenge().type()).isEqualTo("UNKNOWN");
        }
    }

    @Nested
    @DisplayName("O que atravessa para o canal")
    class ChannelContract {

        @Test
        void devolve_a_sessao_da_jornada() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY")));

            assertThat(response.sessionId()).isEqualTo(AUTH_ID);
            assertThat(response.authorizationRequired()).isTrue();
        }

        @Test
        void devolve_a_sessao_tambem_na_espera() {

            ChallengeResponse response = ChallengeMapper.toChallenge(
                    stepWith(pollingCallback("8000", DEEPLINK)));

            assertThat(response.sessionId()).isEqualTo(AUTH_ID);
            assertThat(response.authorizationRequired()).isTrue();
        }
    }

    private static JourneyStep stepWith(Map<String, Object> callback) {
        return new JourneyStep(AUTH_ID, List.of(callback), null);
    }

    private static Map<String, Object> nameCallback(String prompt, String suggested) {
        return Map.of(
                "type", "NameCallback",
                "output", List.of(Map.of("name", "prompt", "value", prompt)),
                "input", List.of(Map.of("name", "IDToken1", "value", suggested)));
    }

    private static Map<String, Object> pollingCallback(String waitTime, String message) {
        return Map.of(
                "type", "PollingWaitCallback",
                "output", List.of(
                        Map.of("name", "waitTime", "value", waitTime),
                        Map.of("name", "message", "value", message)));
    }
}