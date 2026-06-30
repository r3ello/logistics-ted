package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "house")
@Getter
@Setter
@NoArgsConstructor
public class House {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 255)
    private String location;

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "current_phase", length = 1000)
    private String currentPhase;

    @Enumerated(EnumType.STRING)
    @Column(name = "scaffold_status", nullable = false, length = 20)
    private ScaffoldStatus scaffoldStatus = ScaffoldStatus.NONE;

    @Column(name = "scaffold_start_date")
    private LocalDate scaffoldStartDate;

    @Column(name = "scaffold_end_date")
    private LocalDate scaffoldEndDate;

    @Column(name = "checkin_token", length = 64, unique = true)
    private String checkinToken;
}
