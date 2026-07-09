package com.bellgado.logistics_ted.domain;

import com.bellgado.logistics_ted.domain.House;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Table(name = "crew")
@Getter
@Setter
@NoArgsConstructor
public class Crew {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 150)
    private String name;

    /** The manager responsible for this crew. One manager can manage many crews. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private Worker manager;

    /** The single crew leader — DB-enforced, mirrors manager_id pattern. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "leader_id")
    private Worker leader;

    /** The house this crew is currently working on. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "house_id")
    private House house;

    /** The stage type this crew is responsible for. */
    @Column(name = "stage_order")
    private Integer stageOrder;

    @Column(nullable = false, length = 255, columnDefinition = "varchar(255) default ''")
    private String location = "";

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;
}
