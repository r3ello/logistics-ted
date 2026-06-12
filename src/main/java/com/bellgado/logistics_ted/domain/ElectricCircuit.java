package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "electric_circuit")
@Getter @Setter @NoArgsConstructor
public class ElectricCircuit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "box_id", nullable = false)
    private ElectricBox box;

    @Column(name = "slot_index", nullable = false)
    private Integer slotIndex;

    @Column(length = 5, nullable = false)
    private String side = "LEFT";

    @Column(length = 100)
    private String label;

    @Column
    private Integer amps;

    @Column(length = 10, nullable = false)
    private String type = "SINGLE";

    @Column(length = 10, nullable = false)
    private String status = "ON";
}
