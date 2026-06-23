package com.bellgado.logistics_ted.web.dto;

import com.bellgado.logistics_ted.domain.Supplier;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * An external supplier with its stock lines, for the admin management screen. Like
 * {@link DepotDto} but each material line's {@code price} is the supplier's own unit price.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SupplierDto(
    Integer id,
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng,
    List<MaterialLineDto> materials,
    BigDecimal totalValue
) {

    /** Accumulator used while joining a supplier with its inventory rows. */
    public static final class Builder {
        private final Supplier source;
        public final List<MaterialLineDto> materials = new ArrayList<>();
        public BigDecimal totalValue = BigDecimal.ZERO;

        public Builder(Supplier source) {
            this.source = source;
        }

        public SupplierDto build() {
            return new SupplierDto(
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
