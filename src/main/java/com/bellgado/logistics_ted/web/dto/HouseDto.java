package com.bellgado.logistics_ted.web.dto;

import com.bellgado.logistics_ted.domain.House;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record HouseDto(
    Integer id,
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng,
    @JsonProperty("start_date") String startDate,
    @JsonProperty("current_phase") String currentPhase,
    List<MaterialLineDto> materials,
    BigDecimal totalValue
) {

    /** Accumulator used while joining a house with its inventory rows. */
    public static final class Builder {
        private final House source;
        public final List<MaterialLineDto> materials = new ArrayList<>();
        public BigDecimal totalValue = BigDecimal.ZERO;

        public Builder(House source) {
            this.source = source;
        }

        public HouseDto build() {
            return new HouseDto(
                source.getId(),
                source.getName(),
                source.getLocation(),
                source.getLat(),
                source.getLng(),
                source.getStartDate() == null ? null : source.getStartDate().toString(),
                source.getCurrentPhase(),
                materials,
                totalValue
            );
        }
    }
}
