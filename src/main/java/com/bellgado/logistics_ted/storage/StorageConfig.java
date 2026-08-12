package com.bellgado.logistics_ted.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link StorageProperties}. The two {@link ObjectStorage} implementations select themselves
 * on {@code storage.enabled} via {@code @ConditionalOnProperty}, so there is no bean method here —
 * exactly one of them is ever in the context.
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
}
