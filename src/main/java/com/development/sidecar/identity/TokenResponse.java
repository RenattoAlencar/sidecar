package com.development.sidecar.identity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record TokenResponse(@JsonProperty("access_token") String accessToken,
                     @JsonProperty("token_type") String tokenType,
                     @JsonProperty("expires_in") long expiresIn,
                     String scope) {
}