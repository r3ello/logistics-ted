package com.bellgado.logistics_ted.web.dto;

import java.math.BigDecimal;

public record MaterialTotalDto(
    String name,
    String unit,
    BigDecimal price,
    BigDecimal total,
    BigDecimal total_value
) {}
