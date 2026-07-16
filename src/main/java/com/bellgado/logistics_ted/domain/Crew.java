package com.bellgado.logistics_ted.domain;

import com.bellgado.logistics_ted.domain.House;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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

    /** The stages this crew is responsible for (many-to-many via crew_stage_type). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "crew_stage_type", joinColumns = @JoinColumn(name = "crew_id"))
    @Column(name = "stage_order")
    private List<Integer> stageOrders = new ArrayList<>();

    @Column(nullable = false, length = 255, columnDefinition = "varchar(255) default ''")
    private String location = "";

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;
}
