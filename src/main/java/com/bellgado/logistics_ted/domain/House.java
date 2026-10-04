package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "house")
@Getter
@Setter
@NoArgsConstructor
public class House {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 150)
    private String name;

    /**
     * The address text — the CRM's {@code Address}. Optional since V20: the ACTIVE_MASTER sheet has
     * no address column, so readers must fall back (to the name) when it is null.
     */
    @Column(length = 255)
    private String address;

    /** A Google Maps link — the CRM's {@code Location}. Stored opaquely, never resolved to coords. */
    @Column(length = 512)
    private String location;

    @Column(precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "current_phase", length = 1000)
    private String currentPhase;

    @Enumerated(EnumType.STRING)
    @Column(name = "scaffold_status", nullable = false, length = 20)
    private ScaffoldStatus scaffoldStatus = ScaffoldStatus.NONE;

    @Column(name = "scaffold_start_date")
    private LocalDate scaffoldStartDate;

    @Column(name = "scaffold_end_date")
    private LocalDate scaffoldEndDate;

    @Column(name = "checkin_token", length = 64, unique = true)
    private String checkinToken;

    @Column(name = "google_doc_url", length = 512)
    private String googleDocUrl;

    /**
     * The client's own id for this house (CRM id). Unique when set. Also the CSV sync's key: an
     * import row with this key updates this house. Kept in step with {@code import_ref} by
     * {@code HouseService} and {@code HouseImporter} — see Flyway V17.
     */
    @Column(name = "external_id", length = 120)
    private String externalId;

    // ── ACTIVE_MASTER project columns (Flyway V20) ──────────────────────────────────────────────

    /** The end client's name ({@code Client_Name}). Personal data — never log it. */
    @Column(name = "client_name", length = 255)
    private String clientName;

    /** The project's Google Drive folder ({@code Project_Link}). */
    @Column(name = "drive_folder_url", length = 512)
    private String driveFolderUrl;

    /** Google Chat space ({@code Google_Chat_ID}, "spaces/…"). */
    @Column(name = "google_chat_id", length = 120)
    private String googleChatId;

    @Column(name = "google_album_id", length = 255)
    private String googleAlbumId;

    @Column(name = "google_album_url", length = 512)
    private String googleAlbumUrl;

    /** Google Sheets id of the project calculator ({@code Calculator_SS_Id}) — an id, not a URL. */
    @Column(name = "calculator_sheet_id", length = 120)
    private String calculatorSheetId;

    /** Google Sheets id of the project master ({@code Prj_Master_SS_Id}) — an id, not a URL. */
    @Column(name = "master_sheet_id", length = 120)
    private String masterSheetId;

    /** What to show where an address is expected: the address, or the name when there is none (V20). */
    public String addressOrName() {
        return address != null && !address.isBlank() ? address : name;
    }
}
