package com.development.sidecar.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyRequestTest {

    private static final String AUTH_ID = "eyJ0eXAiOiJKV1Qi...";
    private static final String ANSWER = "149707";

    @Nested
    @DisplayName("Resposta a partir do passo recebido")
    class FromStep {

        @Test
        @DisplayName("devolve o callback como o provedor o emitiu")
        void preserva_a_estrutura_recebida() {

            Map<String, Object> body = JourneyRequest.answering(
                    stepWith(nameCallback("PAYLOAD_REQUIRED", "PAYLOAD_REQUIRED")), ANSWER);

            Map<String, Object> callback = firstCallback(body);

            assertThat(callback.get("type")).isEqualTo("NameCallback");
            assertThat(callback).containsKey("output");
        }

        @Test
        @DisplayName("omitir o rótulo faz o provedor recusar: ele precisa voltar")
        void mantem_o_rotulo_emitido_pelo_provedor() {

            Map<String, Object> body = JourneyRequest.answering(
                    stepWith(nameCallback("PAYLOAD_REQUIRED", "PAYLOAD_REQUIRED")), ANSWER);

            assertThat(promptOf(firstCallback(body))).isEqualTo("PAYLOAD_REQUIRED");
        }

        @Test
        void escreve_a_resposta_na_entrada() {

            Map<String, Object> body = JourneyRequest.answering(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY")), ANSWER);

            assertThat(firstInput(firstCallback(body)).get("value")).isEqualTo(ANSWER);
        }

        @Test
        @DisplayName("preserva o nome do campo definido pelo provedor")
        void nao_renomeia_a_entrada() {

            Map<String, Object> callback = new LinkedHashMap<>(
                    nameCallback("OTP_REQUIRED", ""));

            callback.put("input", List.of(mutable("IDToken2", "")));

            Map<String, Object> body = JourneyRequest.answering(
                    stepWith(callback), ANSWER);

            assertThat(firstInput(firstCallback(body)).get("name")).isEqualTo("IDToken2");
        }

        @Test
        void reapresenta_a_sessao_da_jornada() {

            Map<String, Object> body = JourneyRequest.answering(
                    stepWith(nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY")), ANSWER);

            assertThat(body.get("authId")).isEqualTo(AUTH_ID);
        }

        @Test
        @DisplayName("não altera o passo recebido")
        void nao_muta_a_origem() {

            Map<String, Object> original = nameCallback("CHALLENGE_REQUIRED", "OTP:AUTHFY");
            JourneyStep step = stepWith(original);

            JourneyRequest.answering(step, ANSWER);

            assertThat(firstInput(step.callbacks().get(0)).get("value"))
                    .isEqualTo("OTP:AUTHFY");
        }

        @Test
        @DisplayName("só o primeiro callback recebe a resposta")
        void copia_os_demais_intactos() {

            JourneyStep step = new JourneyStep(AUTH_ID, List.of(
                    nameCallback("PRIMEIRO", "A:B"),
                    nameCallback("SEGUNDO", "C:D")), null);

            Map<String, Object> body = JourneyRequest.answering(step, ANSWER);

            List<?> callbacks = (List<?>) body.get("callbacks");

            assertThat(firstInput(asMap(callbacks.get(0))).get("value")).isEqualTo(ANSWER);
            assertThat(firstInput(asMap(callbacks.get(1))).get("value")).isEqualTo("C:D");
        }
    }

    @Nested
    @DisplayName("Passo sem onde escrever")
    class Invalid {

        @Test
        void recusa_passo_sem_callback() {

            JourneyStep step = new JourneyStep(AUTH_ID, List.of(), null);

            assertThatThrownBy(() -> JourneyRequest.answering(step, ANSWER))
                    .isInstanceOf(JourneyRequest.JourneyRequestException.class);
        }

        @Test
        @DisplayName("recusa callback sem entrada: a chamada sairia em branco")
        void recusa_callback_sem_entrada() {

            Map<String, Object> callback = Map.of(
                    "type", "PollingWaitCallback",
                    "output", List.of(Map.of("name", "waitTime", "value", "5000")));

            assertThatThrownBy(() -> JourneyRequest.answering(stepWith(callback), ANSWER))
                    .isInstanceOf(JourneyRequest.JourneyRequestException.class);
        }

        @Test
        void recusa_callback_com_entrada_vazia() {

            Map<String, Object> callback = Map.of(
                    "type", "NameCallback",
                    "input", List.of());

            assertThatThrownBy(() -> JourneyRequest.answering(stepWith(callback), ANSWER))
                    .isInstanceOf(JourneyRequest.JourneyRequestException.class);
        }
    }

    @Nested
    @DisplayName("Resposta remontada, sem o passo em mãos")
    class Remounted {

        @Test
        @DisplayName("monta o callback completo, com rótulo e entrada")
        void monta_no_formato_esperado() {

            Map<String, Object> body = JourneyRequest.answering(
                    AUTH_ID, "CHALLENGE_REQUIRED", ANSWER);

            Map<String, Object> callback = firstCallback(body);

            assertThat(callback.get("type")).isEqualTo("NameCallback");
            assertThat(promptOf(callback)).isEqualTo("CHALLENGE_REQUIRED");
            assertThat(firstInput(callback).get("name")).isEqualTo("IDToken1");
            assertThat(firstInput(callback).get("value")).isEqualTo(ANSWER);
            assertThat(body.get("authId")).isEqualTo(AUTH_ID);
        }

        @Test
        void recusa_sem_identificador_de_jornada() {

            assertThatThrownBy(() -> JourneyRequest.answering(null, "PROMPT", ANSWER))
                    .isInstanceOf(JourneyRequest.JourneyRequestException.class);

            assertThatThrownBy(() -> JourneyRequest.answering("  ", "PROMPT", ANSWER))
                    .isInstanceOf(JourneyRequest.JourneyRequestException.class);
        }
    }

    private static JourneyStep stepWith(Map<String, Object> callback) {
        return new JourneyStep(AUTH_ID, List.of(callback), null);
    }

    private static Map<String, Object> nameCallback(String prompt, String suggested) {

        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("type", "NameCallback");
        callback.put("output", List.of(mutable("prompt", prompt)));
        callback.put("input", List.of(mutable("IDToken1", suggested)));

        return callback;
    }

    private static Map<String, Object> mutable(String name, String value) {

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", name);
        field.put("value", value);

        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstCallback(Map<String, Object> body) {
        return asMap(((List<?>) body.get("callbacks")).get(0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstInput(Map<String, Object> callback) {
        return asMap(((List<?>) callback.get("input")).get(0));
    }

    private static String promptOf(Map<String, Object> callback) {

        for (Object field : (List<?>) callback.get("output")) {
            Map<String, Object> entry = asMap(field);

            if ("prompt".equals(entry.get("name"))) {
                return (String) entry.get("value");
            }
        }
        return null;
    }
}