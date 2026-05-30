package com.bellgado.logistics_ted.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customer_order")
@Getter
@Setter
@NoArgsConstructor
public class CustomerOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "app_user_id")
    private AppUser appUser;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(nullable = false, length = 20)
    private String source;

    @Column(length = 8)
    private String lang;

    @Column(name = "start_lat", nullable = false, precision = 9, scale = 6)
    private BigDecimal startLat;

    @Column(name = "start_lng", nullable = false, precision = 9, scale = 6)
    private BigDecimal startLng;

    @Column(name = "start_name", length = 255)
    private String startName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_house_id")
    private House destinationHouse;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "materials_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> materialsJson = new LinkedHashMap<>();

    @Column(name = "alternatives_count", nullable = false)
    private int alternativesCount;

    @Column(name = "fully_fulfilled", nullable = false)
    private boolean fullyFulfilled;

    @Column(name = "matrix_provider", nullable = false, length = 20)
    private String matrixProvider;

    @Column(name = "compute_ms", nullable = false)
    private int computeMs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chosen_option_id")
    private OrderRouteOption chosenOption;

    @Column(name = "chosen_at")
    private Instant chosenAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("sequenceIndex ASC")
    private List<OrderRouteOption> options = new ArrayList<>();

    public void addOption(OrderRouteOption option) {
        option.setOrder(this);
        options.add(option);
    }
}
