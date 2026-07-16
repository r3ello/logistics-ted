package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "worker")
@Getter
@Setter
@NoArgsConstructor
public class Worker {

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkerRole role = WorkerRole.CREW_MEMBER;

    /** The crew this worker belongs to (leader or member). Null for managers. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "crew_id")
    private Crew crew;

    /** Stages this worker (LEADER/MEMBER) is responsible for — many-to-many via worker_stage_type. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "worker_stage_type", joinColumns = @JoinColumn(name = "worker_id"))
    @Column(name = "stage_order")
    private List<Integer> stageOrders = new ArrayList<>();

    @Column(length = 50)
    private String phone;

    @Column(length = 255)
    private String email;

    @Column(unique = true, length = 60)
    private String username;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "password_plain", length = 100)
    private String passwordPlain;
}
