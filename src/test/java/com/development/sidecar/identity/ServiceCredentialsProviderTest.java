package com.development.sidecar.identity;

import com.development.sidecar.config.ServiceCredentialsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServiceCredentialsProviderTest {

    private static final String URL = "https://sso.invalid/token";
    private static final String USERNAME = "componente";
    private static final String PASSWORD = "segredo";
    private static final String HOST = "sso-exposto.invalid";

    private static final String CREDENTIAL = "eyJhbGciOiJSUzI1NiJ9"
            + ".eyJpc3MiOiJodHRwczovL3Nzby5pbnZhbGlkL3JlYWxtcy9tdWxlc29mdCJ9.assinatura";

    private MockRestServiceServer server;
    private RestClient.Builder builder;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    @Test
    @DisplayName("apresenta o usuário do componente e declara o servidor pretendido")
    void obtem_a_credencial() {

        server.expect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.HOST, HOST))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess(response(3600), MediaType.APPLICATION_JSON));

        ServiceCredentialsProvider provider = provider(Duration.ofSeconds(30));

        assertThat(provider.credential()).isEqualTo(CREDENTIAL);
        server.verify();
    }

    @Test
    @DisplayName("reaproveita a credencial enquanto vale: uma por transação derrubaria o serviço")
    void reaproveita_enquanto_vale() {

        server.expect(ExpectedCount.once(), request -> {})
                .andRespond(withSuccess(response(3600), MediaType.APPLICATION_JSON));

        ServiceCredentialsProvider provider = provider(Duration.ofSeconds(30));

        provider.credential();
        provider.credential();
        provider.credential();

        server.verify();
    }

    @Test
    @DisplayName("renova antes do vencimento: sem folga, chamadas em trânsito chegariam vencidas")
    void renova_com_antecedencia() {

        server.expect(ExpectedCount.twice(), request -> {})
                .andRespond(withSuccess(response(20), MediaType.APPLICATION_JSON));

        ServiceCredentialsProvider provider = provider(Duration.ofSeconds(30));

        provider.credential();
        provider.credential();

        server.verify();
    }

    @Test
    void recusa_credencial_sem_valor() {

        server.expect(request -> {})
                .andRespond(withSuccess("{\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(30)).credential())
                .isInstanceOf(ServiceCredentialsProvider.ServiceCredentialsException.class);
    }

    @Test
    void recusa_quando_o_servico_falha() {

        server.expect(request -> {})
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider(Duration.ofSeconds(30)).credential())
                .isInstanceOf(ServiceCredentialsProvider.ServiceCredentialsException.class);
    }

    @Test
    @DisplayName("sem servidor declarado, o cabeçalho não é enviado")
    void omite_o_servidor_quando_nao_configurado() {

        server.expect(request -> assertThat(request.getHeaders().getFirst(HttpHeaders.HOST))
                        .isNull())
                .andRespond(withSuccess(response(3600), MediaType.APPLICATION_JSON));

        ServiceCredentialsProvider provider = new ServiceCredentialsProvider(
                builder.build(), properties("", Duration.ofSeconds(30)));

        provider.credential();
        server.verify();
    }

    private ServiceCredentialsProvider provider(Duration skew) {
        return new ServiceCredentialsProvider(builder.build(), properties(HOST, skew));
    }

    private static String response(long expiresIn) {
        return "{\"access_token\":\"" + CREDENTIAL + "\",\"expires_in\":" + expiresIn + "}";
    }

    private static ServiceCredentialsProperties properties(String hostHeader, Duration skew) {
        return new ServiceCredentialsProperties(
                URI.create(URL),
                USERNAME,
                PASSWORD,
                hostHeader,
                skew,
                Duration.ofSeconds(2),
                Duration.ofSeconds(5));
    }
}