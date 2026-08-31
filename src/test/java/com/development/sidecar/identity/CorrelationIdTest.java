package com.development.sidecar.identity;

import com.development.sidecar.proxy.CorrelationId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    @DisplayName("aproveita o valor recebido: descartá-lo quebraria a ligação com quem chamou")
    void aproveita_o_recebido() {

        String received = "abc-123_XYZ";

        assertThat(CorrelationId.resolve(received)).isEqualTo(received);
    }

    @Test
    void aceita_identificador_no_formato_usual() {

        String uuid = "84da0844-d1f9-31f9-b4f4-79b420be8be4";

        assertThat(CorrelationId.resolve(uuid)).isEqualTo(uuid);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void gera_um_novo_quando_nao_ha_valor(String received) {

        String resolved = CorrelationId.resolve(received);

        assertThat(resolved).isNotBlank();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "quebra\nde-linha",
            "quebra\rde-linha",
            "com espaco",
            "com/barra",
            "com\u0000nulo",
            "com\"aspas"
    })
    @DisplayName("recusa o que inventaria entradas de registro ou atravessaria para o cabeçalho")
    void gera_um_novo_quando_o_valor_nao_serve(String received) {

        String resolved = CorrelationId.resolve(received);

        assertThat(resolved).isNotEqualTo(received);
        assertThat(resolved).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("recusa valor longo: ele entra em toda linha de registro")
    void recusa_valor_acima_do_teto() {

        String tooLong = "a".repeat(65);

        assertThat(CorrelationId.resolve(tooLong)).isNotEqualTo(tooLong);
    }

    @Test
    void aceita_valor_no_limite() {

        String atLimit = "a".repeat(64);

        assertThat(CorrelationId.resolve(atLimit)).isEqualTo(atLimit);
    }

    @Test
    void gera_valores_distintos() {

        assertThat(CorrelationId.resolve(null)).isNotEqualTo(CorrelationId.resolve(null));
    }
}