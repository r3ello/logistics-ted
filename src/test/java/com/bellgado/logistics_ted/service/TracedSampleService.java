package com.bellgado.logistics_ted.service;

import org.springframework.stereotype.Service;

/**
 * Test-only {@code @Service} target that lives in the business {@code service} package so
 * {@code ServiceLoggingAspect}'s {@code within(...service..*)} pointcut advises it. Used by
 * {@code ServiceLoggingAspectTest}.
 */
@Service
public class TracedSampleService {

    public String greet(String name) {
        return "hi " + name;
    }

    public void boom() {
        throw new IllegalStateException("kaboom");
    }

    public String withToken(String token) {
        return "ok";
    }
}
