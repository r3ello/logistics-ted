package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "doc_folder_template")
@Getter @Setter @NoArgsConstructor
public class DocFolderTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "label_bg", nullable = false, length = 255)
    private String labelBg;

    @Column(name = "label_en", nullable = false, length = 255)
    private String labelEn;

    @Column(name = "parent_id")
    private Integer parentId;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(length = 10)
    private String icon = "";

    @Column(length = 20)
    private String color = "#4f8ef7";
}
