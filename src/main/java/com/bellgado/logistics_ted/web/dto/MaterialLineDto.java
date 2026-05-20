package com.bellgado.logistics_ted.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record MaterialLineDto(
    String name,
    String unit,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal subtotal
) {}
