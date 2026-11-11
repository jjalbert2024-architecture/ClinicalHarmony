package com.clinicalharmony.fhircompliance.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class MdcLoggingFilterTest {

    private final MdcLoggingFilter filter = new MdcLoggingFilter();

    private String runAndCaptureMdc(MockHttpServletRequest request, MockHttpServletResponse response) throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(MDC.get(MdcLoggingFilter.REQUEST_ID_KEY));
        filter.doFilter(request, response, chain);
        return seen.get();
    }

    @Test
    void reusesSafeIncomingCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "gw-7f3a.42_x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(runAndCaptureMdc(request, response)).isEqualTo("gw-7f3a.42_x");
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("gw-7f3a.42_x");
        assertThat(MDC.get(MdcLoggingFilter.REQUEST_ID_KEY)).isNull();
    }

    @Test
    void generatesIdWhenHeaderAbsent() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = runAndCaptureMdc(new MockHttpServletRequest(), response);

        assertThat(id).hasSize(8);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(id);
    }

    @Test
    void replacesUnsafeIncomingIdToProtectLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Correlation-Id", "evil\nINFO fake log line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String id = runAndCaptureMdc(request, response);

        assertThat(id).hasSize(8).doesNotContain("\n");
    }
}
