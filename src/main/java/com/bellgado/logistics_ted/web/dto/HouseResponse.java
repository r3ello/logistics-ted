package com.bellgado.logistics_ted.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record HouseResponse(
    Integer id,
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng,
    @JsonProperty("start_date") String startDate,
    @JsonProperty("current_phase") String currentPhase
) {}
