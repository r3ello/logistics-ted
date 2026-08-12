package com.bellgado.logistics_ted.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Object storage config. The bucket lives on Contabo Object Storage, which speaks the S3 API, so
 * the AWS SDK is pointed at their endpoint instead of AWS.
 *
 * <p>{@code enabled=false} (the default) means no S3 client is built at all and
 * {@link DisabledObjectStorage} takes over — the app boots and every other feature works without
 * credentials. Turn it on per environment.
 *
 * <p>{@code path-style} must stay {@code true} for Contabo: virtual-host addressing puts the bucket
 * in the hostname (`bucket.eu2.contabo.net`), which their endpoint does not serve.
 *
 * <p>{@code access-key} / {@code secret-key} are secrets — they must never reach a log statement.
 * That is also why this class is not exposed through any endpoint.
 */
@ConfigurationProperties("storage")
public record StorageProperties(
    @DefaultValue("false") boolean enabled,
    /** e.g. https://eu2.contabostorage.com — from the Contabo panel, region-specific. */
    @DefaultValue("") String endpoint,
    /** Contabo ignores the region but the SDK requires one; any valid token works. */
    @DefaultValue("us-east-1") String region,
    @DefaultValue("") String bucket,
    @DefaultValue("") String accessKey,
    @DefaultValue("") String secretKey,
    @DefaultValue("true") boolean pathStyle,
    /** Lifetime of the presigned GET URLs handed to the browser. */
    @DefaultValue("15") int presignTtlMinutes,
    /** Lifetime of a presigned PUT. Longer than a GET: a video upload on site 4G takes minutes. */
    @DefaultValue("60") int uploadTtlMinutes,
    /** Per-file ceiling, checked before the upload URL is issued. */
    @DefaultValue("2147483648") long maxFileBytes
) {

    /** True when the values needed to build a client are actually present. */
    public boolean usable() {
        return enabled
            && !endpoint.isBlank()
            && !bucket.isBlank()
            && !accessKey.isBlank()
            && !secretKey.isBlank();
    }
}
