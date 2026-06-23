package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A company-owned warehouse — a tier-2 routing node sitting between houses (tier 1) and
 * external suppliers (tier 3). Named {@code Depot}/{@code depot} internally to avoid a
 * collision with the existing {@link Warehouse}/{@code warehouse} table (which is just 1:1
 * plumbing for house stock); the UI/API label is "Warehouse".
 */
@Entity
@Table(name = "depot")
@Getter
@Setter
@NoArgsConstructor
public class Depot {

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
}
