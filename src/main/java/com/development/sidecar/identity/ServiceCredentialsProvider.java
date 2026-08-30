package com.development.sidecar.identity;

import com.development.sidecar.config.ServiceCredentialsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

public class ServiceCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceCredentialsProvider.class);

    private static final String GRANT_TYPE_FIELD = "grant_type";
    private static final String CLIENT_CREDENTIALS = "client_credentials";

    private static final String RESTRICTED_HEADERS_PROPERTY =
            "jdk.httpclient.allowRestrictedHeaders";
    private static final String HOST_HEADER_NAME = "host";

    private final RestClient restClient;
    private final ServiceCredentialsProperties properties;

    private final AtomicReference<Credential> current = new AtomicReference<>();

    public ServiceCredentialsProvider(RestClient restClient,
                                      ServiceCredentialsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;

        warnIfHostHeaderWillBeDropped();
    }

    private void warnIfHostHeaderWillBeDropped() {

        if (properties.hostHeader().isBlank()) {
            return;
        }

        String allowed = System.getProperty(RESTRICTED_HEADERS_PROPERTY, "");

        boolean hostAllowed = allowed.toLowerCase().contains(HOST_HEADER_NAME);

        if (!hostAllowed) {
            log.warn("O componente está configurado para declarar o servidor pretendido, "
                            + "mas a plataforma não permite: a credencial será emitida pela "
                            + "identidade do endereço chamado, e o serviço seguinte pode recusá-la. "
                            + "Habilite {}={} nas opções da máquina virtual.",
                    RESTRICTED_HEADERS_PROPERTY, HOST_HEADER_NAME);
        }
    }

    public String credential() {

        Credential cached = current.get();

        if (cached != null && cached.validAt(Instant.now(), properties.refreshSkew())) {
            return cached.value();
        }

        Credential renewed = request();
        current.set(renewed);

        return renewed.value();
    }

    private Credential request() {

        log.debug("Obtendo credencial de serviço");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE_FIELD, CLIENT_CREDENTIALS);

        CredentialResponse response;
        try {
            response = restClient.post()
                    .uri(properties.url())
                    .headers(this::applyHost)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(CredentialResponse.class);

        } catch (RestClientException e) {
            throw new ServiceCredentialsException("Falha ao obter a credencial de serviço", e);
        }

        if (response == null || response.accessToken() == null
                || response.accessToken().isBlank()) {

            throw new ServiceCredentialsException("Serviço devolveu credencial sem valor");
        }

        log.info("Credencial de serviço obtida, emitida por {}", issuerOf(response.accessToken()));

        return new Credential(
                response.accessToken(),
                Instant.now().plusSeconds(response.expiresIn()));
    }

    private static String issuerOf(String credential) {
        try {
            int firstDot = credential.indexOf('.');
            int secondDot = credential.indexOf('.', firstDot + 1);

            if (firstDot < 0 || secondDot < 0) {
                return "formato não reconhecido";
            }

            String payload = new String(
                    Base64.getUrlDecoder().decode(credential.substring(firstDot + 1, secondDot)),
                    StandardCharsets.UTF_8);

            var issuer = JsonSupport.readTree(payload).get("iss");

            return issuer == null || issuer.isNull() ? "sem emissor" : issuer.asString();

        } catch (Exception e) {
            return "emissor ilegível";
        }
    }

    private void applyHost(HttpHeaders headers) {
        if (!properties.hostHeader().isBlank()) {
            headers.set(HttpHeaders.HOST, properties.hostHeader());
        }
        headers.setBasicAuth(properties.username(), properties.password());
    }

    private record Credential(String value, Instant expiresAt) {

        boolean validAt(Instant moment, Duration skew) {
            return moment.isBefore(expiresAt.minus(skew));
        }

        @Override
        public String toString() {
            return "Credential[expiresAt=%s]".formatted(expiresAt);
        }
    }

    public static class ServiceCredentialsException extends RuntimeException {

        public ServiceCredentialsException(String message, Throwable cause) {
            super(message, cause);
        }

        public ServiceCredentialsException(String message) {
            super(message);
        }
    }
}