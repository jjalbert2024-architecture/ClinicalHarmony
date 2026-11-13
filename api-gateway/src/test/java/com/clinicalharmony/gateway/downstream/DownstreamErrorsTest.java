package com.clinicalharmony.gateway.downstream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamErrorsTest {

    @Test
    void readsEveryErrorShapeTheServicesUse() {
        assertThat(DownstreamErrors.messageOf("{\"error\":\"Conflict\",\"message\":\"already merged\"}"))
                .isEqualTo("already merged");
        assertThat(DownstreamErrors.messageOf("{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"detail\":\"bad xml\"}"))
                .isEqualTo("bad xml");
        assertThat(DownstreamErrors.messageOf("{\"valid\":false,\"code\":\"ZZZ\",\"reason\":\"Code not found\"}"))
                .isEqualTo("Code not found");
        assertThat(DownstreamErrors.messageOf("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"not-found\",\"diagnostics\":\"Coverage/x not found\"}]}")).isEqualTo("Coverage/x not found");
        assertThat(DownstreamErrors.messageOf("{\"timestamp\":\"t\",\"status\":404,\"error\":\"Not Found\",\"path\":\"/x\"}"))
                .isEqualTo("Not Found");
        assertThat(DownstreamErrors.messageOf("plain text failure")).isEqualTo("plain text failure");
        assertThat(DownstreamErrors.messageOf("")).isEqualTo("(no body)");
        assertThat(DownstreamErrors.messageOf("x".repeat(900))).hasSize(501);
    }
}
