package com.bellgado.logistics_ted.config;

import com.bellgado.logistics_ted.web.logging.RequestCorrelationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the {@link RequestCorrelationFilter} into the servlet chain with an explicit order.
 *
 * <p>Spring Security's {@code FilterChainProxy} registers at order {@code -100}. We register the
 * correlation filter at {@link Ordered#HIGHEST_PRECEDENCE} so it runs <b>first</b> — before the
 * security filters — guaranteeing the {@code requestId} is in the MDC for every log line of the
 * request (auth, controller, service, access log). Registering explicitly avoids the ambiguity of
 * relying on {@code @Order} on an auto-registered {@code @Component} filter relative to Security.
 */
@Configuration
public class WebObservabilityConfig {

    @Bean
    public FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilterRegistration() {
        FilterRegistrationBean<RequestCorrelationFilter> registration =
            new FilterRegistrationBean<>(new RequestCorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("requestCorrelationFilter");
        return registration;
    }
}
