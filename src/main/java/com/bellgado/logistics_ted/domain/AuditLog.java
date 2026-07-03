package com.bellgado.logistics_ted.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One append-only bitácora row: who executed what. Actor ids are plain columns (no
 * {@code @ManyToOne}) on purpose — rows must keep identifying the actor after the
 * user/worker is deleted, and the DB blocks UPDATE via trigger, so never re-save
 * a persisted instance.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant at;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;

    @Column(name = "app_user_id")
    private Integer appUserId;

    @Column(name = "worker_id")
    private Integer workerId;

    @Column(name = "telegram_chat_id")
    private Long telegramChatId;

    @Column(length = 255)
    private String username;

    @Column(length = 32)
    private String role;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false, length = 32)
    private String action;

    @Column(name = "entity_type", length = 64)
    private String entityType;

    @Column(name = "entity_id", length = 64)
    private String entityId;

    @Column(name = "http_method", length = 8)
    private String httpMethod;

    @Column(name = "http_path", length = 512)
    private String httpPath;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details_json", columnDefinition = "jsonb")
    private Map<String, Object> detailsJson;
}
