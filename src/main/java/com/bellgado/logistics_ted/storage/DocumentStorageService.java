package com.bellgado.logistics_ted.storage;

import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.domain.StoredObject;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.StoredObjectRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Puts the three pieces together: the {@code doc_folder} tree decides the key prefix,
 * {@link ObjectStorage} moves the bytes, {@code stored_object} records what landed where.
 *
 * <p>{@code @Component}, not {@code @Service} — see the note on {@link S3ObjectStorage}. Filenames
 * and presigned URLs pass through these signatures, and {@code ServiceLoggingAspect} renders
 * arguments at DEBUG; a presigned URL in a log line is a leaked credential.
 *
 * <p>User uploads never pass through the app: {@link #createUploadUrl} hands the browser a presigned
 * PUT and {@link #confirmUpload} records what the bucket says arrived.
 */
@Component
public class DocumentStorageService {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageService.class);

    /** Guards against a cycle in doc_folder.parent_id, which no constraint prevents. */
    private static final int MAX_DEPTH = 12;

    /** Keep keys short enough to stay well under S3's 1024-byte limit after the prefix. */
    private static final int MAX_SLUG = 80;

    private final ObjectStorage storage;
    private final StorageProperties props;
    private final DocFolderRepository folders;
    private final StoredObjectRepository objects;

    public DocumentStorageService(ObjectStorage storage,
                                  StorageProperties props,
                                  DocFolderRepository folders,
                                  StoredObjectRepository objects) {
        this.storage = storage;
        this.props   = props;
        this.folders = folders;
        this.objects = objects;
    }

    // ── keys ─────────────────────────────────────────────────────────────────

    /**
     * The bucket prefix mirroring a folder's position in the tree, e.g. {@code 02/house_12/photos}.
     *
     * <p>Built from {@code code}, never from the labels: labels are edited by users and translated,
     * while codes are stable slugs. A renamed folder must not orphan its objects.
     */
    public String prefixFor(DocFolder folder) {
        Deque<String> parts = new ArrayDeque<>();
        DocFolder cur = folder;
        int depth = 0;
        while (cur != null && depth++ < MAX_DEPTH) {
            parts.addFirst(sanitiseSegment(cur.getCode()));
            cur = cur.getParent();
        }
        return String.join("/", parts);
    }

    /** Lower-cased ASCII, so the same folder never yields two spellings of one prefix. */
    private static String sanitiseSegment(String raw) {
        if (raw == null || raw.isBlank()) return "_";
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "_" : s;
    }

    /**
     * Keys are ASCII-only even though S3 accepts UTF-8: Cyrillic filenames are the norm here, and a
     * non-ASCII key turns every tool that touches the bucket (CLI, web console, log line) into a
     * percent-encoding puzzle. The real name survives in {@code original_filename} and comes back
     * on download through {@code Content-Disposition}.
     */
    static String sanitiseFilename(String raw) {
        if (raw == null || raw.isBlank()) return "file";
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);       // strip any path the browser sent

        String base = name;
        String ext  = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            ext  = name.substring(dot + 1).replaceAll("[^A-Za-z0-9]+", "");
        }
        String slug = base.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "file";
        if (slug.length() > MAX_SLUG) slug = slug.substring(0, MAX_SLUG);
        return ext.isEmpty() ? slug : slug + "." + ext.toLowerCase(Locale.ROOT);
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /**
     * Step 1 of a direct upload: reserve the key, record a {@code pending} row, and hand back a
     * presigned PUT the browser uploads to itself.
     *
     * <p>This exists because video does not fit the proxied path: a 300 MB file would be buffered
     * to Tomcat's temp directory and then re-sent to the bucket, doubling the traffic and holding a
     * request thread for minutes. Here the bytes never touch the app.
     *
     * <p>The server, never the client, decides the key — otherwise a caller could write anywhere in
     * the bucket.
     */
    @Transactional
    public UploadTicket createUploadUrl(Integer folderId, String filename, String contentType,
                                        long declaredSize, String uploadedBy) {
        if (declaredSize <= 0) {
            throw new IllegalArgumentException("Empty file.");
        }
        if (declaredSize > props.maxFileBytes()) {
            throw new IllegalArgumentException(
                "File is larger than the " + (props.maxFileBytes() / (1024 * 1024)) + " MB limit.");
        }
        DocFolder folder = folders.findById(folderId)
            .orElseThrow(() -> new EntityNotFoundException("Folder not found"));

        UUID publicId = UUID.randomUUID();
        String key = prefixFor(folder) + "/" + publicId + "-" + sanitiseFilename(filename);
        String type = (contentType == null || contentType.isBlank())
            ? "application/octet-stream" : contentType;

        StoredObject o = new StoredObject();
        o.setPublicId(publicId);
        o.setObjectKey(key);
        o.setBucket(props.bucket());
        o.setFolder(folder);
        o.setOriginalFilename(trim(filename, 255));
        o.setContentType(trim(type, 150));
        o.setSizeBytes(declaredSize);          // provisional — replaced by the bucket's own figure
        o.setStatus("pending");
        o.setUploadedBy(trim(uploadedBy, 100));
        objects.save(o);

        String url = storage.presignedPut(key, Duration.ofMinutes(props.uploadTtlMinutes()), type);
        return new UploadTicket(publicId, url, type);
    }

    /**
     * Step 2: the bucket is asked what actually arrived, and the row flips to {@code ready}.
     *
     * <p>The size comes from the HEAD, not from the browser — a client could claim anything at step
     * 1. If the object is not there the row stays {@code pending} and the caller gets an error,
     * which is what makes a half-finished upload invisible instead of broken.
     */
    @Transactional
    public StoredObject confirmUpload(UUID publicId) {
        StoredObject o = objects.findByPublicId(publicId)
            .orElseThrow(() -> new EntityNotFoundException("File not found"));

        var head = storage.head(o.getObjectKey())
            .orElseThrow(() -> new StorageException("Upload did not complete — nothing at that key."));

        o.setSizeBytes(head.sizeBytes());
        o.setEtag(trim(head.etag(), 100));
        if (head.contentType() != null && !head.contentType().isBlank()) {
            o.setContentType(trim(head.contentType(), 150));
        }
        o.setStatus("ready");
        return objects.save(o);
    }

    /** What the browser needs to perform the upload itself. */
    public record UploadTicket(UUID fileId, String uploadUrl, String contentType) {}

    /**
     * Removes the row and then the object. Inside one transaction: if the bucket delete throws, the
     * row is rolled back, so the file never disappears from the listing while still existing.
     */
    @Transactional
    public void delete(UUID publicId) {
        StoredObject o = objects.findByPublicId(publicId)
            .orElseThrow(() -> new EntityNotFoundException("File not found"));
        objects.delete(o);
        storage.delete(o.getObjectKey());
    }

    // ── reads ────────────────────────────────────────────────────────────────

    /** Only {@code ready} rows: an upload still in flight must not appear as a file. */
    @Transactional(readOnly = true)
    public List<StoredObject> list(Integer folderId) {
        return objects.findReadyByFolderId(folderId);
    }

    /** Bucket bootstrap so browsers may PUT directly. One-time, admin-triggered. */
    public void configureCors(List<String> origins) {
        storage.configureCors(origins);
    }

    @Transactional(readOnly = true)
    public StoredObject require(UUID publicId) {
        return objects.findByPublicId(publicId)
            .orElseThrow(() -> new EntityNotFoundException("File not found"));
    }

    /** A short-lived direct URL, so the bytes never pass back through this application. */
    public String downloadUrl(StoredObject o) {
        return storage.presignedGet(
            o.getObjectKey(),
            Duration.ofMinutes(props.presignTtlMinutes()),
            o.getOriginalFilename());
    }

    // ── folder mirroring ─────────────────────────────────────────────────────

    /**
     * Creates the marker objects for a folder and everything under it, so a freshly created house
     * is browsable in the Contabo console before anyone uploads anything.
     *
     * <p><b>Call this only from {@link StorageEventListener}</b>, never inline from a create path.
     * It costs one PUT per node — 12 for a house with its template — and a 150-row CSV import would
     * otherwise hold one database transaction open for the ~1800 sequential round trips.
     *
     * <p>Best-effort by contract: failures are logged and swallowed. The prefixes appear on first
     * upload anyway, since S3 needs no folder to exist; these markers are purely cosmetic.
     */
    public void mirrorTree(DocFolder root) {
        try {
            List<DocFolder> queue = new ArrayList<>();
            queue.add(root);
            for (int i = 0; i < queue.size() && i < 500; i++) {
                DocFolder f = queue.get(i);
                storage.createFolder(prefixFor(f));
                queue.addAll(folders.findByParentId(f.getId()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not mirror folder tree '{}' into the bucket: {}",
                root.getCode(), e.getMessage());
        }
    }

    /**
     * Drops every object under a prefix. Called when a house is deleted, so its bytes are not
     * orphaned once the {@code doc_folder} rows go by FK cascade.
     *
     * <p>Takes the prefix rather than the folder because it runs <b>after</b> the deleting
     * transaction commits, by which time the rows it was derived from no longer exist. Running it
     * before the commit is what would be unsafe: a later rollback would resurrect the rows with
     * their files already erased.
     */
    public void deleteTreeByPrefix(String prefix) {
        try {
            storage.deletePrefix(prefix.endsWith("/") ? prefix : prefix + "/");
        } catch (RuntimeException e) {
            log.warn("Could not delete bucket prefix '{}': {}", prefix, e.getMessage());
        }
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
