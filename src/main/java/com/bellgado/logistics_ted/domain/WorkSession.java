package com.bellgado.logistics_ted.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "work_session")
@Getter @Setter @NoArgsConstructor
public class WorkSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "house_id", nullable = false)
    private House house;

    @Column(name = "session_date", nullable = false)
    private java.time.LocalDate sessionDate;

    @Column(name = "checked_in_at", nullable = false)
    private OffsetDateTime checkedInAt;

    @Column(name = "checked_out_at")
    private OffsetDateTime checkedOutAt;

    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;

    @Column(name = "check_in_lat", precision = 9, scale = 6)
    private BigDecimal checkInLat;

    @Column(name = "check_in_lng", precision = 9, scale = 6)
    private BigDecimal checkInLng;

    @Column(name = "check_out_lat", precision = 9, scale = 6)
    private BigDecimal checkOutLat;

    @Column(name = "check_out_lng", precision = 9, scale = 6)
    private BigDecimal checkOutLng;
}
