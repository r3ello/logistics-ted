package com.bellgado.logistics_ted.storage;

import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Contabo Object Storage through the S3 API.
 *
 * <p>{@code @Component}, deliberately not {@code @Service}: {@code ServiceLoggingAspect} advises
 * {@code @Service} beans under {@code ..service..} and renders arguments at DEBUG. An
 * {@code InputStream} of file bytes in an advised signature is exactly what must never happen.
 *
 * <p>The pom excludes both bundled HTTP transports, so {@code httpClient(...)} below is not a
 * preference — without it the SDK cannot find a transport and throws on the first call.
 */
@Component
@ConditionalOnProperty(prefix = "storage", name = "enabled", havingValue = "true")
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorage(StorageProperties props) {
        if (!props.usable()) {
            throw new IllegalStateException(
                "storage.enabled=true but endpoint/bucket/access-key/secret-key are incomplete. "
                    + "Set STORAGE_ENDPOINT, STORAGE_BUCKET, STORAGE_ACCESS_KEY and STORAGE_SECRET_KEY.");
        }
        this.bucket = props.bucket();

        var credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        var endpoint = URI.create(props.endpoint());
        var region = Region.of(props.region());
        // Path-style: the bucket goes in the path, not the hostname. Required by Contabo.
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build();

        this.s3 = S3Client.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            .httpClient(UrlConnectionHttpClient.create())
            .build();

        // The presigner opens no connection — it only signs — so it needs no httpClient.
        this.presigner = S3Presigner.builder()
            .endpointOverride(endpoint)
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(s3Config)
            .build();

        // Endpoint and bucket are not secrets; the keys are, and are never logged.
        log.info("Object storage ready: bucket '{}' at {}", bucket, endpoint.getHost());
    }

    @Override
    public String put(String key, InputStream data, long size, String contentType) {
        try {
            var req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType == null ? "application/octet-stream" : contentType)
                .contentLength(size)
                .build();
            return s3.putObject(req, RequestBody.fromInputStream(data, size)).eTag();
        } catch (S3Exception e) {
            throw new StorageException("Upload failed for key '" + key + "'", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception e) {
            throw new StorageException("Delete failed for key '" + key + "'", e);
        }
    }

    @Override
    public void deletePrefix(String prefix) {
        List<StoredItem> items = list(prefix);
        if (items.isEmpty()) return;
        try {
            // DeleteObjects caps at 1000 keys per call.
            for (int from = 0; from < items.size(); from += 1000) {
                List<ObjectIdentifier> batch = items.subList(from, Math.min(from + 1000, items.size()))
                    .stream()
                    .map(i -> ObjectIdentifier.builder().key(i.key()).build())
                    .toList();
                s3.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(batch).build())
                    .build());
            }
        } catch (S3Exception e) {
            throw new StorageException("Delete failed for prefix '" + prefix + "'", e);
        }
    }

    @Override
    public List<StoredItem> list(String prefix) {
        try {
            List<StoredItem> out = new ArrayList<>();
            String token = null;
            do {
                var req = ListObjectsV2Request.builder()
                    .bucket(bucket).prefix(prefix).continuationToken(token).build();
                var res = s3.listObjectsV2(req);
                res.contents().forEach(o -> out.add(new StoredItem(o.key(), o.size(), o.eTag())));
                token = Boolean.TRUE.equals(res.isTruncated()) ? res.nextContinuationToken() : null;
            } while (token != null);
            return out;
        } catch (S3Exception e) {
            throw new StorageException("List failed for prefix '" + prefix + "'", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw new StorageException("Head failed for key '" + key + "'", e);
        }
    }

    @Override
    public void createFolder(String prefix) {
        String key = prefix.endsWith("/") ? prefix : prefix + "/";
        try {
            s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentLength(0L).build(),
                RequestBody.empty());
        } catch (S3Exception e) {
            throw new StorageException("Could not create folder marker '" + key + "'", e);
        }
    }

    @Override
    public String presignedGet(String key, Duration ttl, String downloadFilename) {
        var get = GetObjectRequest.builder().bucket(bucket).key(key);
        if (downloadFilename != null && !downloadFilename.isBlank()) {
            get.responseContentDisposition(contentDisposition(downloadFilename));
        }
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(get.build())
            .build());
        return presigned.url().toString();
    }

    @Override
    public String presignedPut(String key, Duration ttl, String contentType) {
        // contentType is signed in: the browser must send the identical header or the bucket 403s.
        var put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType == null ? "application/octet-stream" : contentType)
            .build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .putObjectRequest(put)
            .build());
        return presigned.url().toString();
    }

    @Override
    public Optional<ObjectHead> head(String key) {
        try {
            var res = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new ObjectHead(res.contentLength(), res.eTag(), res.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return Optional.empty();
            throw new StorageException("Head failed for key '" + key + "'", e);
        }
    }

    @Override
    public void configureCors(List<String> origins) {
        try {
            var rule = CORSRule.builder()
                .allowedOrigins(origins)
                .allowedMethods("GET", "PUT", "HEAD")
                .allowedHeaders("*")
                // The browser reads neither on a direct PUT, but exposing them makes debugging sane.
                .exposeHeaders("ETag", "Content-Length")
                .maxAgeSeconds(3600)
                .build();
            s3.putBucketCors(PutBucketCorsRequest.builder()
                .bucket(bucket)
                .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                .build());
            log.info("Bucket CORS set for origins {}", origins);
        } catch (S3Exception e) {
            throw new StorageException("Could not set bucket CORS", e);
        }
    }

    /**
     * RFC 5987 form so non-ASCII names (Cyrillic is the norm here) survive the header. The plain
     * {@code filename} is kept as an ASCII fallback for older clients.
     */
    private static String contentDisposition(String filename) {
        String ascii = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    @PreDestroy
    void close() {
        s3.close();
        presigner.close();
    }
}
