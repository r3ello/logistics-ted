package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.StoredObject;
import com.bellgado.logistics_ted.storage.DocumentStorageService;
import jakarta.persistence.EntityNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Files attached to a {@code doc_folder} node — site photos and video, contracts, worker paperwork.
 *
 * <p><b>No bytes pass through this application, in either direction.</b> Uploading is a three-step
 * handshake — ask for a URL, PUT straight to the bucket, confirm — and downloading returns a
 * presigned GET. That is what allows a 300 MB site video without buffering it into the app or
 * holding a request thread for the length of the transfer.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final DocumentStorageService files;

    public FileController(DocumentStorageService files) {
        this.files = files;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam Integer folderId) {
        List<Map<String, Object>> out = files.list(folderId).stream()
            .map(FileController::toDto)
            .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * Step 1 — reserve a key and get the URL to PUT to. The browser must send exactly the
     * {@code contentType} returned here, because it is part of the signature.
     */
    @PostMapping("/upload-url")
    public ResponseEntity<?> uploadUrl(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            Integer folderId = ((Number) body.get("folderId")).intValue();
            String filename  = String.valueOf(body.getOrDefault("filename", "file"));
            String type      = body.get("contentType") == null ? null : String.valueOf(body.get("contentType"));
            long size        = body.get("sizeBytes") == null ? 0L : ((Number) body.get("sizeBytes")).longValue();

            var ticket = files.createUploadUrl(folderId, filename, type, size,
                auth == null ? null : auth.getName());
            return ResponseEntity.ok(Map.of(
                "fileId", ticket.fileId(),
                "uploadUrl", ticket.uploadUrl(),
                "contentType", ticket.contentType()));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            return ResponseEntity.badRequest().body(
                Map.of("error", e.getMessage() == null ? "folderId, filename and sizeBytes are required." : e.getMessage()));
        }
    }

    /**
     * Step 3 — the bucket is asked what landed, and the file becomes visible. Until this is called
     * the row stays {@code pending} and the file appears in no listing.
     */
    @PostMapping("/{publicId}/confirm")
    public ResponseEntity<?> confirm(@PathVariable UUID publicId) {
        try {
            return ResponseEntity.ok(toDto(files.confirmUpload(publicId)));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** The presigned URL is a bearer credential for that object until it expires — never log it. */
    @GetMapping("/{publicId}/url")
    public ResponseEntity<?> url(@PathVariable UUID publicId) {
        try {
            StoredObject o = files.require(publicId);
            return ResponseEntity.ok(
                Map.of("url", files.downloadUrl(o), "filename", o.getOriginalFilename()));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Admin-only, unlike the rest of the doc surface: this erases bytes from the bucket and there is
     * no undo, whereas deleting a doc_document row only drops a link someone can paste again.
     */
    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> delete(@PathVariable UUID publicId) {
        try {
            files.delete(publicId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * One-time bucket bootstrap. A direct PUT from the browser is a cross-origin XHR, so without a
     * CORS rule every upload dies at the preflight. Exposed as an endpoint because Contabo's panel
     * does not necessarily surface CORS and this avoids requiring the AWS CLI on the operator's
     * machine. Idempotent — it overwrites the bucket's CORS configuration each time.
     */
    @PostMapping("/bucket-cors")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> bucketCors(@RequestBody Map<String, Object> body) {
        Object origins = body.get("origins");
        List<String> list = origins instanceof List<?> l
            ? l.stream().map(String::valueOf).toList()
            : List.of();
        if (list.isEmpty()) {
            return ResponseEntity.badRequest().body(
                Map.of("error", "origins must be a non-empty list, e.g. [\"https://app.example.com\"]"));
        }
        files.configureCors(list);
        return ResponseEntity.ok(Map.of("ok", true, "origins", list));
    }

    private static Map<String, Object> toDto(StoredObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getPublicId());
        m.put("filename", o.getOriginalFilename());
        m.put("contentType", o.getContentType());
        m.put("sizeBytes", o.getSizeBytes());
        m.put("uploadedBy", o.getUploadedBy());
        m.put("createdAt", o.getCreatedAt() == null ? null : o.getCreatedAt().toString());
        return m;
    }
}
