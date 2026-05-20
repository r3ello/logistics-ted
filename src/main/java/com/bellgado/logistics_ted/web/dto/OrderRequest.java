package com.bellgado.logistics_ted.web.dto;

import java.util.Map;

public record OrderRequest(
    Double startLat,
    Double startLng,
    String startName,
    Integer destinationHouseId,
    Map<String, Object> materials,
    String lang
) {}
