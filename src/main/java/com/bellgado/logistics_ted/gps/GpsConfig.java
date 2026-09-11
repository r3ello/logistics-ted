package com.bellgado.logistics_ted.gps;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link GpsHunterProperties}. {@link GpsHunterClient} and {@link GpsPoller} select themselves on
 * {@code gps.enabled} via {@code @ConditionalOnProperty}; the read side ({@link VehicleTrackingService},
 * {@link GpsSyncState}) always exists so the fleet screens work in every environment.
 */
@Configuration
@EnableConfigurationProperties(GpsHunterProperties.class)
public class GpsConfig {
}
