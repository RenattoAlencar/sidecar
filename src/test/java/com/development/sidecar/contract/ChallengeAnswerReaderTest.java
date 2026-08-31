package com.development.sidecar.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeAnswerReaderTest {

    private final ChallengeAnswerReader reader =
            new ChallengeAnswerReader(JsonMapper.builder().build());

    @Test
    void le_a_resposta_apresentada() {

        String answer = reader.read(body("""
                {"authz":{"response":"149707"}}"""));

        assertThat(answer).isEqualTo("149707");
    }

    @Test
    @DisplayName("aceita resposta longa: o corpo existe para caber o que cabeçalho não cabe")
    void le_resposta_longa() {

        String large = "e".repeat(20_000);

        String answer = reader.read(body(
                "{\"authz\":{\"response\":\"" + large + "\"}}"));

        assertThat(answer).isEqualTo(large);
    }

    @Test
    @DisplayName("ignora o que vier além do bloco de autorização")
    void ignora_campos_desconhecidos() {

        String answer = reader.read(body("""
                {"channel":{"amount":10.25},"authz":{"response":"149707","extra":"x"}}"""));

        assertThat(answer).isEqualTo("149707");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{}",
            "{\"authz\":{}}",
            "{\"authz\":{\"response\":\"\"}}",
            "{\"authz\":{\"response\":\"   \"}}",
            "{\"authz\":null}",
            "{\"response\":\"149707\"}",
            "nao e json",
            "[]"
    })
    @DisplayName("corpo que não traz a resposta devolve nada")
    void sem_resposta(String json) {
        assertThat(reader.read(body(json))).isNull();
    }

    @Test
    void tolera_corpo_ausente() {

        assertThat(reader.read(null)).isNull();
        assertThat(reader.read(new byte[0])).isNull();
    }

    @Test
    @DisplayName("preserva acento e caractere fora do alfabeto latino")
    void preserva_o_conteudo() {

        String answer = reader.read(body("""
                {"authz":{"response":"não-identificado"}}"""));

        assertThat(answer).isEqualTo("não-identificado");
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}