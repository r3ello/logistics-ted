package com.bellgado.logistics_ted.web.dto;

import java.math.BigDecimal;

public record SupplierUpsertRequest(
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng
) {}
