package com.bellgado.logistics_ted.web.dto;

import com.bellgado.logistics_ted.domain.Depot;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * A company warehouse (depot) with its stock lines, for the admin management screen.
 * Shape mirrors {@link HouseDto} (minus the house-only project fields).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record DepotDto(
    Integer id,
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng,
    List<MaterialLineDto> materials,
    BigDecimal totalValue
) {

    /** Accumulator used while joining a depot with its inventory rows. */
    public static final class Builder {
        private final Depot source;
        public final List<MaterialLineDto> materials = new ArrayList<>();
        public BigDecimal totalValue = BigDecimal.ZERO;

        public Builder(Depot source) {
            this.source = source;
        }

        public DepotDto build() {
            return new DepotDto(
                source.getId(),
                source.getName(),
                source.getLocation(),
                source.getLat(),
                source.getLng(),
                materials,
                totalValue
            );
        }
    }
}
