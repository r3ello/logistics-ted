package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file stored in the object bucket. See {@code V9__stored_object.sql} for why the metadata lives
 * here rather than being derived from the bucket, and why files hang off {@link DocFolder}.
 */
@Entity
@Table(name = "stored_object")
@Getter
@Setter
@NoArgsConstructor
public class StoredObject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The only id the API exposes. */
    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "object_key", nullable = false, unique = true, length = 1024)
    private String objectKey;

    @Column(nullable = false, length = 100)
    private String bucket;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = false)
    private DocFolder folder;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 100)
    private String etag;

    /** {@code pending} until the bucket confirms the bytes landed; {@code ready} afterwards. */
    @Column(nullable = false, length = 10)
    private String status = "ready";

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
