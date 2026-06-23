package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock of one material held at one {@link Depot}. Mirrors {@link SupplierInventory} but
 * without a unit price — depot stock is owned, already valued by {@code material.price}.
 */
@Entity
@Table(
    name = "depot_inventory",
    uniqueConstraints = @UniqueConstraint(name = "uq_depot_inv", columnNames = {"depot_id", "material_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class DepotInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "depot_id", nullable = false)
    private Depot depot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal quantity;
}
