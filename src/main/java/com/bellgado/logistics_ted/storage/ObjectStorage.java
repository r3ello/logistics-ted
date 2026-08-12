package com.bellgado.logistics_ted.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * The byte store, behind an interface so the rest of the app never imports the AWS SDK.
 *
 * <p><b>There are no directories in S3.</b> A "folder" is nothing but a shared key prefix, so
 * {@link #createFolder} writes a zero-byte marker object and exists only to make the bucket
 * browsable in Contabo's web UI before anything has been uploaded. The real hierarchy is the
 * {@code doc_folder} tree in Postgres; the bucket mirrors it in key prefixes. Nothing in the app
 * should ever read structure back out of the bucket.
 *
 * <p>Implementations must not log keys' contents, credentials, or presigned URLs (a presigned URL
 * is a bearer credential for the object until it expires).
 */
public interface ObjectStorage {

    /** One object as listed under a prefix. */
    record StoredItem(String key, long sizeBytes, String etag) {}

    /** What the bucket reports about a single object. */
    record ObjectHead(long sizeBytes, String etag, String contentType) {}

    /**
     * Writes {@code key} from a stream the server already holds. User uploads do NOT come through
     * here — they go browser-to-bucket via {@link #presignedPut}. This is for content the app
     * generates itself (the empty folder markers today).
     *
     * @param size the exact byte count — S3 requires the length up front.
     * @return the object's ETag.
     */
    String put(String key, InputStream data, long size, String contentType);

    /** Deletes one object. Succeeds silently when the key does not exist, as S3 does. */
    void delete(String key);

    /** Deletes every object under a prefix. Used when a folder or a house is removed. */
    void deletePrefix(String prefix);

    List<StoredItem> list(String prefix);

    boolean exists(String key);

    /** Zero-byte marker so an empty prefix is visible in the Contabo UI. See the class note. */
    void createFolder(String prefix);

    /**
     * A time-limited URL the browser can GET directly, so file bytes never pass through the app.
     *
     * @param downloadFilename when set, comes back as a {@code Content-Disposition} header, which
     *                         is how the original (possibly Cyrillic) filename survives a key that
     *                         was sanitised to ASCII.
     */
    String presignedGet(String key, Duration ttl, String downloadFilename);

    /**
     * A time-limited URL the browser can PUT the file to, so large uploads (video) never pass
     * through the application at all.
     *
     * <p>The {@code contentType} is part of the signature: the browser must send exactly this value
     * as its {@code Content-Type} header or the bucket rejects the request.
     *
     * <p>Unlike a presigned GET opened by navigation, a PUT from JavaScript is a cross-origin XHR,
     * so the bucket needs a CORS rule allowing PUT from the app's origin — see
     * {@link #configureCors}.
     */
    String presignedPut(String key, Duration ttl, String contentType);

    /** What the bucket holds at {@code key}, or empty when nothing is there. */
    java.util.Optional<ObjectHead> head(String key);

    /**
     * One-time bucket bootstrap: allow browsers on {@code origins} to PUT and GET directly.
     * Without it every direct upload fails the CORS preflight.
     */
    void configureCors(List<String> origins);
}
