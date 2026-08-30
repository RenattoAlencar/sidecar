package com.development.sidecar.identity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record CredentialResponse(@JsonProperty("access_token") String accessToken,
                          @JsonProperty("expires_in") long expiresIn) {
}