package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "house_stage_crew_log")
@Getter @Setter @NoArgsConstructor
public class HouseStageCrewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "house_id", nullable = false)
    private Integer houseId;

    @Column(name = "house_name", nullable = false)
    private String houseName;

    @Column(name = "stage_order", nullable = false)
    private Integer stageOrder;

    @Column(name = "stage_name", nullable = false, length = 120)
    private String stageName;

    @Column(name = "stage_name_en", length = 120)
    private String stageNameEn;

    @Column(name = "crew_id")
    private Integer crewId;

    @Column(name = "crew_name")
    private String crewName;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "logged_at", nullable = false)
    private OffsetDateTime loggedAt = OffsetDateTime.now();
}
