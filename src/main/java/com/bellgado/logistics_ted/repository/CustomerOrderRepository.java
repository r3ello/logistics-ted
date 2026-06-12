package com.bellgado.logistics_ted.repository;

import com.bellgado.logistics_ted.domain.CustomerOrder;
import com.bellgado.logistics_ted.repository.projection.OrderEventRow;
import com.bellgado.logistics_ted.repository.projection.OrderOptionRow;
import com.bellgado.logistics_ted.repository.projection.OrderSummaryRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, Long> {

    /**
     * Kept for the write path (recordView / recordChoice) where we need the managed entity.
     * Do NOT use this for the read-only history/detail endpoints — it triggers lazy proxies
     * (appUser, destinationHouse, chosenOption) that the controller can't resolve once the
     * service transaction closes (open-in-view is disabled).
     */
    Optional<CustomerOrder> findByPublicId(UUID publicId);

    // ─── READ-ONLY DTO PROJECTIONS (no lazy entities, no @EntityGraph) ─────────────────

    @Query(
        value = """
            SELECT
              co.public_id                            AS publicId,
              co.created_at                           AS createdAt,
              co.source                               AS source,
              u.username                              AS username,
              co.telegram_chat_id                     AS telegramChatId,
              co.start_lat                            AS startLat,
              co.start_lng                            AS startLng,
              co.start_name                           AS startName,
              co.destination_house_id                 AS destinationHouseId,
              h.name                                  AS destinationHouseName,
              co.materials_json::text                 AS materialsJson,
              co.app_user_id                          AS appUserId,
              co.alternatives_count                   AS alternativesCount,
              co.fully_fulfilled                      AS fullyFulfilled,
              chosen.objective                        AS chosenObjective,
              co.chosen_at                            AS chosenAt
            FROM customer_order co
            LEFT JOIN app_user u           ON u.id = co.app_user_id
            LEFT JOIN house h              ON h.id = co.destination_house_id
            LEFT JOIN order_route_option chosen ON chosen.id = co.chosen_option_id
            ORDER BY co.created_at DESC
            """,
        countQuery = "SELECT COUNT(*) FROM customer_order",
        nativeQuery = true)
    Page<OrderSummaryRow> findAllSummaries(Pageable pageable);

    @Query(
        value = """
            SELECT
              co.public_id                            AS publicId,
              co.created_at                           AS createdAt,
              co.source                               AS source,
              u.username                              AS username,
              co.telegram_chat_id                     AS telegramChatId,
              co.start_lat                            AS startLat,
              co.start_lng                            AS startLng,
              co.start_name                           AS startName,
              co.destination_house_id                 AS destinationHouseId,
              h.name                                  AS destinationHouseName,
              co.materials_json::text                 AS materialsJson,
              co.app_user_id                          AS appUserId,
              co.alternatives_count                   AS alternativesCount,
              co.fully_fulfilled                      AS fullyFulfilled,
              chosen.objective                        AS chosenObjective,
              co.chosen_at                            AS chosenAt
            FROM customer_order co
            LEFT JOIN app_user u           ON u.id = co.app_user_id
            LEFT JOIN house h              ON h.id = co.destination_house_id
            LEFT JOIN order_route_option chosen ON chosen.id = co.chosen_option_id
            WHERE co.app_user_id = :appUserId
            ORDER BY co.created_at DESC
            """,
        countQuery = "SELECT COUNT(*) FROM customer_order WHERE app_user_id = :appUserId",
        nativeQuery = true)
    Page<OrderSummaryRow> findSummariesByAppUser(@Param("appUserId") Integer appUserId, Pageable pageable);

    @Query(
        value = """
            SELECT
              co.public_id                            AS publicId,
              co.created_at                           AS createdAt,
              co.source                               AS source,
              u.username                              AS username,
              co.telegram_chat_id                     AS telegramChatId,
              co.start_lat                            AS startLat,
              co.start_lng                            AS startLng,
              co.start_name                           AS startName,
              co.destination_house_id                 AS destinationHouseId,
              h.name                                  AS destinationHouseName,
              co.materials_json::text                 AS materialsJson,
              co.app_user_id                          AS appUserId,
              co.alternatives_count                   AS alternativesCount,
              co.fully_fulfilled                      AS fullyFulfilled,
              chosen.objective                        AS chosenObjective,
              co.chosen_at                            AS chosenAt
            FROM customer_order co
            LEFT JOIN app_user u           ON u.id = co.app_user_id
            LEFT JOIN house h              ON h.id = co.destination_house_id
            LEFT JOIN order_route_option chosen ON chosen.id = co.chosen_option_id
            WHERE co.public_id = :publicId
            """,
        nativeQuery = true)
    Optional<OrderSummaryRow> findSummaryByPublicId(@Param("publicId") UUID publicId);

    @Query(
        value = """
            SELECT
              co.public_id                            AS publicId,
              co.created_at                           AS createdAt,
              co.source                               AS source,
              u.username                              AS username,
              co.telegram_chat_id                     AS telegramChatId,
              co.start_lat                            AS startLat,
              co.start_lng                            AS startLng,
              co.start_name                           AS startName,
              co.destination_house_id                 AS destinationHouseId,
              h.name                                  AS destinationHouseName,
              co.materials_json::text                 AS materialsJson,
              co.app_user_id                          AS appUserId,
              co.alternatives_count                   AS alternativesCount,
              co.fully_fulfilled                      AS fullyFulfilled,
              chosen.objective                        AS chosenObjective,
              co.chosen_at                            AS chosenAt
            FROM customer_order co
            LEFT JOIN app_user u           ON u.id = co.app_user_id
            LEFT JOIN house h              ON h.id = co.destination_house_id
            LEFT JOIN order_route_option chosen ON chosen.id = co.chosen_option_id
            WHERE co.telegram_chat_id = :chatId
            ORDER BY co.created_at DESC
            """,
        countQuery = "SELECT COUNT(*) FROM customer_order WHERE telegram_chat_id = :chatId",
        nativeQuery = true)
    Page<OrderSummaryRow> findSummariesByTelegramChat(@Param("chatId") Long chatId, Pageable pageable);

    @Query(
        value = """
            SELECT
              opt.public_id              AS publicId,
              opt.objective              AS objective,
              opt.is_primary             AS isPrimary,
              opt.sequence_index         AS sequenceIndex,
              opt.total_distance_km      AS totalDistanceKm,
              opt.total_minutes          AS totalMinutes,
              opt.total_stops            AS totalStops,
              opt.supplier_stops_count   AS supplierStopsCount,
              opt.fully_fulfilled        AS fullyFulfilled,
              opt.maps_url               AS mapsUrl,
              opt.first_viewed_at        AS firstViewedAt,
              opt.last_viewed_at         AS lastViewedAt,
              opt.view_count             AS viewCount,
              (opt.id = co.chosen_option_id) AS isChosen,
              opt.payload_json::text     AS payloadJson
            FROM order_route_option opt
            JOIN customer_order co ON co.id = opt.order_id
            WHERE co.public_id = :publicId
            ORDER BY opt.sequence_index ASC
            """,
        nativeQuery = true)
    List<OrderOptionRow> findOptionRowsByOrderPublicId(@Param("publicId") UUID publicId);

    @Query(
        value = """
            SELECT
              e.event_type           AS eventType,
              e.at                   AS at,
              opt.objective          AS objective,
              u.username             AS username,
              e.metadata_json::text  AS metadataJson
            FROM order_event e
            JOIN customer_order co            ON co.id = e.order_id
            LEFT JOIN order_route_option opt  ON opt.id = e.option_id
            LEFT JOIN app_user u              ON u.id = e.app_user_id
            WHERE co.public_id = :publicId
            ORDER BY e.at ASC
            """,
        nativeQuery = true)
    List<OrderEventRow> findEventRowsByOrderPublicId(@Param("publicId") UUID publicId);
}
