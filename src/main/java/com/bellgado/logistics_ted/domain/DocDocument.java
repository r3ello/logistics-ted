package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "doc_document")
@Getter @Setter @NoArgsConstructor
public class DocDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id", nullable = false)
    private DocFolder folder;

    @Column(name = "title_en", nullable = false, length = 255)
    private String titleEn;

    @Column(name = "title_bg", nullable = false, length = 255)
    private String titleBg = "";

    @Column(name = "link_url", length = 1000)
    private String linkUrl;

    @Column(name = "doc_type", nullable = false, length = 20)
    private String docType = "PDF";

    @Column(length = 500)
    private String description;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();
}
