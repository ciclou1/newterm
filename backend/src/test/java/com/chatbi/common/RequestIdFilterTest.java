package com.chatbi.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    @Test
    void echoesSafeIncomingIdIntoMdcAttributeAndResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/query");
        request.addHeader(RequestIdFilter.HEADER, "edge-abc.123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        FilterChain chain = (req, res) -> seen.set(MDC.get(RequestIdFilter.REQUEST_ID));
        new RequestIdFilter().doFilter(request, response, chain);

        assertThat(seen.get()).isEqualTo("edge-abc.123");
        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID)).isEqualTo("edge-abc.123");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("edge-abc.123");
        assertThat(MDC.get(RequestIdFilter.REQUEST_ID)).isNull();
    }

    @Test
    void replacesHeaderInjectionAndOversizedIds() throws Exception {
        for (String hostile : new String[]{"bad id\n", "x".repeat(65), ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/query");
            request.addHeader(RequestIdFilter.HEADER, hostile);
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestIdFilter().doFilter(request, response, (req, res) -> { });

            assertThat(response.getHeader(RequestIdFilter.HEADER))
                    .isNotEqualTo(hostile)
                    .matches("[0-9a-f-]{12}");
        }
    }
}
