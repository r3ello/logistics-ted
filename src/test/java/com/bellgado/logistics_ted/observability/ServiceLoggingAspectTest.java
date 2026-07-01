package com.bellgado.logistics_ted.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bellgado.logistics_ted.service.TracedSampleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * Pure unit test — no Spring context, no Postgres. Weaves the aspect onto a sample @Service (in the
 * business service package, so the pointcut matches) and asserts it (a) returns the real result,
 * (b) traces entry/exit at DEBUG, (c) logs a WARN and rethrows the original exception on failure, and
 * (d) NEVER logs a JWT / bearer token passed as an argument.
 */
class ServiceLoggingAspectTest {

    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        aspectLogger = (Logger) LoggerFactory.getLogger(ServiceLoggingAspect.class);
        aspectLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        aspectLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        aspectLogger.detachAppender(appender);
    }

    private TracedSampleService proxied() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new TracedSampleService());
        factory.addAspect(new ServiceLoggingAspect(1000));
        return factory.getProxy();
    }

    @Test
    void returnsResultAndTracesEntryAndExit() {
        String result = proxied().greet("ana");

        assertThat(result).isEqualTo("hi ana");
        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anyMatch(m -> m.contains("TracedSampleService.greet") && m.contains("ana"))   // entry, with arg
            .anyMatch(m -> m.contains("TracedSampleService.greet") && m.contains("ms"));   // timed exit
    }

    @Test
    void logsWarnAndRethrowsOriginalException() {
        TracedSampleService proxy = proxied();

        assertThatThrownBy(proxy::boom)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("kaboom");
        assertThat(appender.list)
            .anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("TracedSampleService.boom")
                && e.getFormattedMessage().contains("FAILED"));
    }

    @Test
    void neverLogsAJwtArgument() {
        String jwt = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhZG1pbiIsImV4cCI6MTc4Mjg3ODgxM30.aBcDeFgHiJkLmNoP";

        proxied().withToken(jwt);

        assertThat(appender.list)
            .extracting(ILoggingEvent::getFormattedMessage)
            .noneMatch(m -> m.contains(jwt))                                                   // token never present
            .anyMatch(m -> m.contains("TracedSampleService.withToken") && m.contains("<redacted>"));
    }
}
