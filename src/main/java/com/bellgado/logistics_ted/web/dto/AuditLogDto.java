package com.bellgado.logistics_ted.web.dto;

import java.time.Instant;
import java.util.Map;

/** Read-only bitácora row as exposed by {@code GET /api/audit}. */
public record AuditLogDto(
    Long id,
    Instant at,
    String actorType,
    String username,
    String role,
    Long telegramChatId,
    String source,
    String action,
    String entityType,
    String entityId,
    String httpMethod,
    String httpPath,
    Integer httpStatus,
    String clientIp,
    String requestId,
    Map<String, Object> details
) {}
