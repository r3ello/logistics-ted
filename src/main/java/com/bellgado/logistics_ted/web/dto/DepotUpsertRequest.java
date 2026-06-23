package com.bellgado.logistics_ted.web.dto;

import java.math.BigDecimal;

public record DepotUpsertRequest(
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng
) {}
