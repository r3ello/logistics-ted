package com.bellgado.logistics_ted.web.dto;

import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record HouseUpsertRequest(
    String name,
    String address,
    String location,
    BigDecimal lat,
    BigDecimal lng,
    @JsonProperty("start_date") String startDate,
    @JsonProperty("current_phase") String currentPhase,
    ScaffoldStatus scaffoldStatus,
    @JsonProperty("scaffoldStartDate") String scaffoldStartDate,
    @JsonProperty("scaffoldEndDate")   String scaffoldEndDate,
    @JsonProperty("google_doc_url")    String googleDocUrl,
    @JsonProperty("external_id")       String externalId,
    // ACTIVE_MASTER project columns (V20). null = untouched, "" = clear.
    @JsonProperty("client_name")         String clientName,
    @JsonProperty("drive_folder_url")    String driveFolderUrl,
    @JsonProperty("google_chat_id")      String googleChatId,
    @JsonProperty("google_album_id")     String googleAlbumId,
    @JsonProperty("google_album_url")    String googleAlbumUrl,
    @JsonProperty("calculator_sheet_id") String calculatorSheetId,
    @JsonProperty("master_sheet_id")     String masterSheetId
) {}
