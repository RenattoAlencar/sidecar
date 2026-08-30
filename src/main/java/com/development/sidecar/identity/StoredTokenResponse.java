package com.development.sidecar.identity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record StoredTokenResponse(Dados dados) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Dados(String tokenRef) {
    }

    String tokenRef() {
        return dados == null ? null : dados.tokenRef();
    }
}