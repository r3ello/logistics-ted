package com.bellgado.logistics_ted.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stand-in when {@code storage.enabled} is false — the default, so a developer with no Contabo
 * credentials still gets a booting app and a working dashboard.
 *
 * <p>Reads degrade quietly ({@link #list} is empty, {@link #exists} is false) so a Documents view
 * renders "no files" instead of an error, while anything that would write or hand out a URL fails
 * loudly with a 503. Silently accepting an upload and dropping the bytes would be far worse.
 */
@Component
@ConditionalOnProperty(prefix = "storage", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledObjectStorage implements ObjectStorage {

    private static final String MSG =
        "File storage is not configured on this environment (storage.enabled=false).";

    @Override
    public String put(String key, InputStream data, long size, String contentType) {
        throw new StorageException.Unavailable(MSG);
    }

    @Override
    public void delete(String key) {
        throw new StorageException.Unavailable(MSG);
    }

    @Override
    public void deletePrefix(String prefix) {
        // No-op: nothing was ever written, and this runs inside house deletion, which must not fail.
    }

    @Override
    public List<StoredItem> list(String prefix) {
        return List.of();
    }

    @Override
    public boolean exists(String key) {
        return false;
    }

    @Override
    public void createFolder(String prefix) {
        // No-op: folder mirroring is best-effort and must never break house creation.
    }

    @Override
    public String presignedGet(String key, Duration ttl, String downloadFilename) {
        throw new StorageException.Unavailable(MSG);
    }

    @Override
    public String presignedPut(String key, Duration ttl, String contentType) {
        throw new StorageException.Unavailable(MSG);
    }

    @Override
    public java.util.Optional<ObjectHead> head(String key) {
        return java.util.Optional.empty();
    }

    @Override
    public void configureCors(List<String> origins) {
        throw new StorageException.Unavailable(MSG);
    }
}
