package com.development.sidecar.proxy;

import com.development.sidecar.config.ProxyProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestForwarderTest {

    private static final String PATH = "/api/v1/pix/transferencia";
    private static final String BODY = "{\"channel\":{\"message\":\"não identificado\"}}";
    private static final long MAX_BODY = 1024L;

    private HttpServer target;
    private final AtomicReference<Received> received = new AtomicReference<>();

    private RequestForwarder forwarder;

    @BeforeEach
    void setUp() throws IOException {

        target = HttpServer.create(new InetSocketAddress(0), 0);

        target.createContext("/", exchange -> {

            Map<String, String> headers = new HashMap<>();
            exchange.getRequestHeaders()
                    .forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));

            byte[] body = exchange.getRequestBody().readAllBytes();

            received.set(new Received(headers, new String(body, StandardCharsets.UTF_8)));

            byte[] answer = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Do-Destino", "presente");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });

        target.start();

        forwarder = new RequestForwarder(
                HttpClient.newBuilder().build(),
                properties(target.getAddress().getPort()),
                new ProxyHeaderPolicy(List.of("x-reservado")));
    }

    @AfterEach
    void tearDown() {
        target.stop(0);
    }

    @Nested
    @DisplayName("Delimitação da requisição")
    class Framing {

        @Test
        @DisplayName("recusa comprimento e codificação declarados juntos")
        void recusa_declaracao_dupla() {

            MockHttpServletRequest request = request();
            request.addHeader("Transfer-Encoding", "chunked");
            request.addHeader("Content-Length", "10");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        @DisplayName("recusa comprimentos divergentes")
        void recusa_comprimentos_divergentes() {

            MockHttpServletRequest request = request();
            request.addHeader("Content-Length", "10");
            request.addHeader("Content-Length", "20");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        void recusa_comprimento_ilegivel() {

            MockHttpServletRequest request = request();
            request.addHeader("Content-Length", "dez");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        void recusa_comprimento_negativo() {

            MockHttpServletRequest request = request();
            request.addHeader("Content-Length", "-1");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        @DisplayName("recusa corpo acima do teto antes de lê-lo")
        void recusa_corpo_declarado_acima_do_teto() {

            MockHttpServletRequest request = request();
            request.addHeader("Content-Length", String.valueOf(MAX_BODY + 1));

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE);
        }

        @Test
        void aceita_declaracao_coerente() {

            MockHttpServletRequest request = request();
            request.addHeader("Content-Length", "10");

            assertThat(forwarder.framingRejection(request)).isEmpty();
        }

        @Test
        void aceita_comprimento_repetido_com_o_mesmo_valor() {

            MockHttpServletRequest request = request();
            request.addHeader("Content-Length", "10");
            request.addHeader("Content-Length", "10");

            assertThat(forwarder.framingRejection(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Leitura do corpo")
    class BodyReading {

        @Test
        @DisplayName("devolve o corpo como veio, sem alterar conteúdo")
        void le_o_corpo_intacto() throws IOException {

            byte[] body = forwarder.readBody(request());

            assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo(BODY);
        }

        @Test
        void recusa_corpo_acima_do_teto() {

            MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
            request.setContent(new byte[(int) MAX_BODY + 1]);

            assertThatThrownBy(() -> forwarder.readBody(request))
                    .isInstanceOf(RequestForwarder.PayloadTooLargeException.class);
        }

        @Test
        @DisplayName("método sem corpo não tem o que ler")
        void metodo_sem_corpo() throws IOException {

            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);

            assertThat(forwarder.readBody(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Encaminhamento")
    class Forwarding {

        @Test
        @DisplayName("o corpo chega ao destino sem alteração")
        void encaminha_o_corpo() throws IOException {

            forwarder.forward(request(), new MockHttpServletResponse(), Map.of(), null);

            assertThat(received.get().body()).isEqualTo(BODY);
        }

        @Test
        void encaminha_o_corpo_ja_lido() throws IOException {

            byte[] body = "{\"outro\":true}".getBytes(StandardCharsets.UTF_8);

            forwarder.forward(request(), new MockHttpServletResponse(), Map.of(), body);

            assertThat(received.get().body()).isEqualTo("{\"outro\":true}");
        }

        @Test
        @DisplayName("cabeçalhos comuns atravessam")
        void copia_cabecalhos_do_chamador() throws IOException {

            MockHttpServletRequest request = request();
            request.addHeader("x-porto-authentication", "jwt-do-canal");

            forwarder.forward(request, new MockHttpServletResponse(), Map.of(), null);

            assertThat(received.get().headers())
                    .containsEntry("x-porto-authentication", "jwt-do-canal");
        }

        @Test
        @DisplayName("o que o chamador declara num cabeçalho reservado é descartado")
        void descarta_reservado_do_chamador() throws IOException {

            MockHttpServletRequest request = request();
            request.addHeader("x-reservado", "valor-do-chamador");

            forwarder.forward(request, new MockHttpServletResponse(), Map.of(), null);

            assertThat(received.get().headers()).doesNotContainKey("x-reservado");
        }

        @Test
        @DisplayName("o valor escrito pelo componente prevalece sobre o do chamador")
        void o_componente_prevalece() throws IOException {

            MockHttpServletRequest request = request();
            request.addHeader("x-reservado", "valor-do-chamador");

            forwarder.forward(request, new MockHttpServletResponse(),
                    Map.of("x-reservado", "valor-do-componente"), null);

            assertThat(received.get().headers())
                    .containsEntry("x-reservado", "valor-do-componente");
        }

        @Test
        @DisplayName("acrescenta o próprio salto à cadeia recebida")
        void acrescenta_a_cadeia_de_encaminhamento() throws IOException {

            MockHttpServletRequest request = request();
            request.addHeader("X-Forwarded-For", "203.0.113.10");
            request.setRemoteAddr("10.0.0.1");

            forwarder.forward(request, new MockHttpServletResponse(), Map.of(), null);

            assertThat(received.get().headers().get("x-forwarded-for"))
                    .isEqualTo("203.0.113.10, 10.0.0.1");
        }

        @Test
        @DisplayName("cabeçalhos da conexão não atravessam")
        void nao_copia_cabecalhos_da_conexao() throws IOException {

            MockHttpServletRequest request = request();
            request.addHeader("Keep-Alive", "timeout=5");

            forwarder.forward(request, new MockHttpServletResponse(), Map.of(), null);

            assertThat(received.get().headers()).doesNotContainKey("keep-alive");
        }

        @Test
        @DisplayName("valor vazio não é escrito: diria que o componente decidiu algo")
        void ignora_valor_vazio() throws IOException {

            forwarder.forward(request(), new MockHttpServletResponse(),
                    Map.of("x-reservado", ""), null);

            assertThat(received.get().headers()).doesNotContainKey("x-reservado");
        }
    }

    @Nested
    @DisplayName("Resposta do destino")
    class Response {

        @Test
        void devolve_status_corpo_e_cabecalhos() throws IOException {

            MockHttpServletResponse response = new MockHttpServletResponse();

            forwarder.forward(request(), response, Map.of(), null);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
            assertThat(response.getHeader("X-Do-Destino")).isEqualTo("presente");
        }
    }

    @Nested
    @DisplayName("Destino inalcançável")
    class Unreachable {

        @Test
        void traduz_falha_de_contato() {

            RequestForwarder unreachable = new RequestForwarder(
                    HttpClient.newBuilder().build(),
                    properties(1),
                    new ProxyHeaderPolicy(List.of()));

            assertThatThrownBy(() -> unreachable.forward(
                    request(), new MockHttpServletResponse(), Map.of(), null))
                    .isInstanceOf(RequestForwarder.UpstreamException.class);
        }
    }

    private static MockHttpServletRequest request() {

        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContentType("application/json");
        request.setContent(BODY.getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("10.0.0.1");

        return request;
    }

    private static ProxyProperties properties(int port) {
        return new ProxyProperties(
                URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                MAX_BODY,
                List.of(),
                "x-correlation-id");
    }

    private record Received(Map<String, String> headers, String body) {
    }
}