package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of {@code stage_type}, mapped only so JPQL can order {@link HouseStage}s by the
 * stage's display position. Writes to {@code stage_type} go through {@code HouseStageController}.
 *
 * <p>{@code stageOrder} is the stage's identity (every stage FK points at it); {@code sortOrder} is
 * where it is listed (Flyway V19).
 */
@Entity
@Immutable
@Table(name = "stage_type")
@Getter
@NoArgsConstructor
public class StageType {

    @Id
    @Column(name = "stage_order")
    private Integer stageOrder;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
