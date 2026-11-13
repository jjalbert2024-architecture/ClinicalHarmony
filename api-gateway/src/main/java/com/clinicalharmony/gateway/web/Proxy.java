package com.clinicalharmony.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;

/** Passes a downstream 2xx through: its status and JSON body, none of its headers. */
final class Proxy {

    private Proxy() {
    }

    static ResponseEntity<JsonNode> relay(ResponseEntity<JsonNode> downstream) {
        return ResponseEntity.status(downstream.getStatusCode()).body(downstream.getBody());
    }
}
