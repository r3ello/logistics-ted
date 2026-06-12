package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "electric_box")
@Getter @Setter @NoArgsConstructor
public class ElectricBox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @Column(name = "main_amps", nullable = false)
    private Integer mainAmps = 200;

    @Column(length = 150)
    private String label;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "box", cascade = CascadeType.ALL)
    @OrderBy("slotIndex ASC")
    private List<ElectricCircuit> circuits = new ArrayList<>();
}
