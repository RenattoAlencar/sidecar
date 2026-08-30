package com.development.sidecar.identity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record RetrievedTokenResponse(Dados dados) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dados(String accessToken) {
    }

    String accessToken() {
        return dados == null ? null : dados.accessToken();
    }
}