package com.bellgado.logistics_ted.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtProperties {
    /**
     * HS256 signing secret. Must be at least 32 bytes; the JwtService pads short dev secrets so
     * a local default keeps the app bootable, but production MUST set TEDHOUSE_JWT_SECRET.
     */
    private String secret = "";

    /** Token lifetime in minutes. Default 480 (8h) to match the previous session timeout. */
    private long ttlMinutes = 480;

    /** `iss` claim — purely informational, used for log lines and future cross-service hops. */
    private String issuer = "logistics-ted";
}
