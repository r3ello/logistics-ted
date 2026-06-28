package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "house_stage")
@Getter @Setter @NoArgsConstructor
public class HouseStage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    @Column(name = "stage_name", nullable = false, length = 120)
    private String stageName;

    @Column(name = "stage_name_en", length = 120)
    private String stageNameEn;

    @Column(name = "crew_id")
    private Integer crewId;

    @Column(name = "worker_name", length = 120)
    private String workerName;

    @Column(nullable = false, length = 30)
    private String status = "NOT_STARTED";

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
