package com.bellgado.logistics_ted.web.dto;

import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record HouseUpsertRequest(
    String name,
    String location,
    BigDecimal lat,
    BigDecimal lng,
    @JsonProperty("start_date") String startDate,
    @JsonProperty("current_phase") String currentPhase,
    ScaffoldStatus scaffoldStatus,
    @JsonProperty("scaffoldStartDate") String scaffoldStartDate,
    @JsonProperty("scaffoldEndDate")   String scaffoldEndDate
) {}
