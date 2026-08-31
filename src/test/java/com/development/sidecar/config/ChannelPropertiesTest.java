package com.development.sidecar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelPropertiesTest {

    @Test
    void expoe_os_tres_cabecalhos_como_reservados() {

        ChannelProperties properties = new ChannelProperties("x-sessao", "x-resposta", "x-ref");

        assertThat(properties.reservedNames())
                .containsExactlyInAnyOrder("x-sessao", "x-resposta", "x-ref");
    }

    @Test
    @DisplayName("nomes iguais fariam continuação e efetivação chegarem pelo mesmo lugar")
    void recusa_nomes_repetidos() {

        assertThatThrownBy(() -> new ChannelProperties("x-mesmo", "x-resposta", "x-mesmo"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ChannelProperties("x-sessao", "x-mesmo", "x-mesmo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a comparação não depende de caixa: o destino também não dependeria")
    void recusa_nomes_repetidos_em_outra_grafia() {

        assertThatThrownBy(() -> new ChannelProperties("X-Mesmo", "x-resposta", "x-mesmo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remove_espaco_em_volta_dos_nomes() {

        ChannelProperties properties =
                new ChannelProperties("  x-sessao  ", "x-resposta", "x-ref");

        assertThat(properties.sessionHeader()).isEqualTo("x-sessao");
    }
}