package com.development.sidecar.contract;

import com.development.sidecar.identity.JourneyStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeMapperTest {

    private static final String AUTH_ID = "eyJ0eXAiOiJKV1Qi...";

    @Nested
    @DisplayName("Tipo do desafio")
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
        @DisplayName("callback de espera não nomeia desafio")
        void callback_de_espera() {

            Map<String, Object> polling = Map.of(
                    "type", "PollingWaitCallback",
                    "output", List.of(
                            Map.of("name", "waitTime", "value", "5000"),
                            Map.of("name", "message", "value", "Please wait...")));

            ChallengeResponse response = ChallengeMapper.toChallenge(stepWith(polling));

            assertThat(response.challenge().type()).isEqualTo("UNKNOWN");
        }

        @Test
        void callback_sem_entrada_nem_rotulo() {

            Map<String, Object> callback = Map.of("type", "NameCallback");

            ChallengeResponse response = ChallengeMapper.toChallenge(stepWith(callback));

            assertThat(response.challenge().type()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("mais de um callback não é reconhecido")
        void varios_callbacks() {

            JourneyStep step = new JourneyStep(AUTH_ID, List.of(
                    nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY"),
                    nameCallback("OTHER", "X:Y")), null);

            ChallengeResponse response = ChallengeMapper.toChallenge(step);

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
}