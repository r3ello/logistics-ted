package com.bellgado.logistics_ted.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Pure unit test — no Spring context, no Postgres. Verifies the correlation id is available inside
 * the chain, echoed on the response, honours an inbound header, and that the MDC is cleared after
 * the request so nothing leaks onto the pooled request thread.
 */
class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesRequestId_echoesHeader_andClearsMdcAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/houses");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] insideChain = new String[1];
        FilterChain chain = (req, res) -> insideChain[0] = MDC.get(RequestCorrelationFilter.REQUEST_ID);

        filter.doFilter(request, response, chain);

        assertThat(insideChain[0]).isNotBlank();
        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo(insideChain[0]);
        assertThat(MDC.get(RequestCorrelationFilter.REQUEST_ID)).isNull();
    }

    @Test
    void honoursInboundRequestIdHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/calculate-order");
        request.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "abc-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] insideChain = new String[1];
        FilterChain chain = (req, res) -> insideChain[0] = MDC.get(RequestCorrelationFilter.REQUEST_ID);

        filter.doFilter(request, response, chain);

        assertThat(insideChain[0]).isEqualTo("abc-123");
        assertThat(response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo("abc-123");
    }

    @Test
    void logsEmittedInTheMiddleCarryTheRequestId() throws Exception {
        Logger appLogger = (Logger) LoggerFactory.getLogger("test.during.request");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        appLogger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/houses");
            MockHttpServletResponse response = new MockHttpServletResponse();
            // A service/controller somewhere in the middle logs something.
            FilterChain chain = (req, res) -> appLogger.info("work happening in the middle");

            filter.doFilter(request, response, chain);

            // The captured event carries the requestId in its MDC — which the console pattern
            // (%X{requestId}) renders — and it equals the id echoed on the response header, so a
            // client can tie an API call to all of its log lines.
            String echoed = response.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.get(0).getMDCPropertyMap())
                .containsEntry(RequestCorrelationFilter.REQUEST_ID, echoed);
        } finally {
            appLogger.detachAppender(appender);
        }
    }
}
