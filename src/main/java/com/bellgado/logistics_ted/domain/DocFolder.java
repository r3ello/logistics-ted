package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "doc_folder")
@Getter @Setter @NoArgsConstructor
public class DocFolder {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(name = "folder_type", length = 50)
    private String folderType;

    @Column(name = "label_en", nullable = false, length = 255)
    private String labelEn;

    @Column(name = "label_bg", nullable = false, length = 255)
    private String labelBg;

    @Column(length = 10)
    private String icon = "";

    @Column(length = 20)
    private String color = "#4f8ef7";

    @Column(name = "link_url", length = 1000)
    private String linkUrl;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private DocFolder parent;
}
