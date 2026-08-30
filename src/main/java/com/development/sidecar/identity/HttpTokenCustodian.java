package com.development.sidecar.identity;

import com.development.sidecar.config.TokenHandlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.Map;

public class HttpTokenCustodian implements TokenCustodian {

    private static final Logger log = LoggerFactory.getLogger(HttpTokenCustodian.class);

    private static final String ACCESS_TOKEN_FIELD = "accessToken";

    private final RestClient restClient;
    private final TokenHandlerProperties properties;
    private final ServiceCredentialsProvider credentials;

    public HttpTokenCustodian(RestClient restClient,
                              TokenHandlerProperties properties,
                              ServiceCredentialsProvider credentials) {
        this.restClient = restClient;
        this.properties = properties;
        this.credentials = credentials;
    }

    @Override
    public TokenReference store(AccessToken token) {

        if (token == null) {
            throw new TokenCustodyException("Nenhum token a entregar sob guarda");
        }

        log.debug("Entregando o token sob guarda");

        StoredTokenResponse response;
        try {
            response = restClient.post()
                    .uri(properties.url())
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(Map.of(ACCESS_TOKEN_FIELD, token.accessToken()))
                    .retrieve()
                    .body(StoredTokenResponse.class);

        } catch (ServiceCredentialsProvider.ServiceCredentialsException e) {
            throw new TokenCustodyException(
                    "Sem credencial para entregar o token sob guarda", e);

        } catch (RestClientException e) {
            throw new TokenCustodyException("Falha ao entregar o token sob guarda", e);
        }

        String tokenRef = response == null ? null : response.tokenRef();

        if (tokenRef == null || tokenRef.isBlank()) {
            throw new TokenCustodyException("Guardião recebeu o token e não devolveu referência");
        }

        log.debug("Token sob guarda");

        return new TokenReference(tokenRef);
    }

    @Override
    public AccessToken retrieve(String tokenRef) {

        if (tokenRef == null || tokenRef.isBlank()) {
            throw new TokenNotFoundException("Referência ausente");
        }

        log.debug("Recuperando o token pela referência");

        RetrievedTokenResponse response;
        try {
            response = restClient.get()
                    .uri(properties.url())
                    .headers(headers -> {
                        authorize(headers);
                        headers.set(properties.tokenRefHeader(), tokenRef);
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(RetrievedTokenResponse.class);

        } catch (RestClientResponseException e) {
            throw translate(e);

        } catch (ServiceCredentialsProvider.ServiceCredentialsException e) {
            throw new TokenCustodyException("Sem credencial para recuperar o token", e);

        } catch (RestClientException e) {
            throw new TokenCustodyException("Falha ao recuperar o token sob guarda", e);
        }

        String accessToken = response == null ? null : response.accessToken();

        if (accessToken == null || accessToken.isBlank()) {
            throw new TokenNotFoundException("Guardião respondeu sem token para a referência");
        }

        log.debug("Token recuperado");

        return new AccessToken(accessToken, null, Duration.ZERO, null);
    }

    private RuntimeException translate(RestClientResponseException e) {

        int status = e.getStatusCode().value();

        if (status == HttpStatus.NOT_FOUND.value()) {
            return new TokenNotFoundException("Referência não corresponde a token guardado");
        }

        log.warn("Status inesperado do guardião ao recuperar: {}", status);

        return new TokenCustodyException("Guardião respondeu status " + status);
    }

    private void authorize(HttpHeaders headers) {
        headers.setBearerAuth(credentials.credential());
    }
}