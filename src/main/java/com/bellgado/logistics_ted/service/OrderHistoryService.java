package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.config.RoutingProperties;
import com.bellgado.logistics_ted.domain.AppUser;
import com.bellgado.logistics_ted.domain.CustomerOrder;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.OrderEvent;
import com.bellgado.logistics_ted.domain.OrderRouteOption;
import com.bellgado.logistics_ted.repository.AppUserRepository;
import com.bellgado.logistics_ted.repository.CustomerOrderRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.OrderEventRepository;
import com.bellgado.logistics_ted.repository.OrderRouteOptionRepository;
import com.bellgado.logistics_ted.repository.projection.OrderEventRow;
import com.bellgado.logistics_ted.repository.projection.OrderOptionRow;
import com.bellgado.logistics_ted.repository.projection.OrderSummaryRow;
import com.bellgado.logistics_ted.security.AppUserDetailsService.AuthenticatedUser;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderDetailDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderEventDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderSummaryDto;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists every {@code POST /api/calculate-order} call, the alternatives it produced,
 * and the user's subsequent view/choose actions. Writes happen in {@code REQUIRES_NEW}
 * transactions so a history failure cannot poison the caller's main flow.
 */
@Service
public class OrderHistoryService {

    private static final Logger log = LoggerFactory.getLogger(OrderHistoryService.class);

    public enum Source { DASHBOARD, TELEGRAM, API }

    private final CustomerOrderRepository orders;
    private final OrderRouteOptionRepository options;
    private final OrderEventRepository events;
    private final AppUserRepository users;
    private final HouseRepository houses;
    private final ObjectMapper mapper;
    private final RoutingProperties routingProperties;

    public OrderHistoryService(CustomerOrderRepository orders,
                               OrderRouteOptionRepository options,
                               OrderEventRepository events,
                               AppUserRepository users,
                               HouseRepository houses,
                               ObjectMapper mapper,
                               RoutingProperties routingProperties) {
        this.orders = orders;
        this.options = options;
        this.events = events;
        this.users = users;
        this.houses = houses;
        this.mapper = mapper;
        this.routingProperties = routingProperties;
    }

    /**
     * Persists the calculation. Returns a (orderPublicId, objective→optionPublicId map) so
     * the controller can decorate the {@link OrderResponse} with the IDs the frontend uses
     * for follow-up view/choose calls.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecordResult recordCalculation(OrderRequest req, OrderResponse resp,
                                          Source source, Integer appUserId,
                                          Long telegramChatId, long computeMs) {
        CustomerOrder order = new CustomerOrder();
        order.setPublicId(UUID.randomUUID());
        order.setCreatedAt(Instant.now());
        if (appUserId != null) {
            users.findById(appUserId).ifPresent(order::setAppUser);
        }
        order.setTelegramChatId(telegramChatId);
        order.setSource(source.name().toLowerCase(Locale.ROOT));
        order.setLang(req.lang());
        order.setStartLat(BigDecimal.valueOf(req.startLat()));
        order.setStartLng(BigDecimal.valueOf(req.startLng()));
        order.setStartName(req.startName());
        if (req.destinationHouseId() != null) {
            houses.findById(req.destinationHouseId()).ifPresent(order::setDestinationHouse);
        }
        order.setMaterialsJson(toStringKeyMap(req.materials()));

        List<RouteOptionDto> alts = resp.alternatives() == null ? List.of() : resp.alternatives();
        order.setAlternativesCount(alts.size());
        order.setFullyFulfilled(resp.fullyFulfilled());
        order.setMatrixProvider(routingProperties.provider());
        order.setComputeMs((int) Math.min(computeMs, Integer.MAX_VALUE));

        for (int i = 0; i < alts.size(); i++) {
            RouteOptionDto alt = alts.get(i);
            OrderRouteOption opt = new OrderRouteOption();
            opt.setPublicId(UUID.randomUUID());
            opt.setObjective(alt.objective());
            opt.setPrimary(i == 0);
            opt.setSequenceIndex(i);
            opt.setTotalDistanceKm((int) alt.totalDistance());
            opt.setTotalMinutes((int) alt.totalMinutes());
            opt.setTotalStops(alt.totalStops());
            opt.setSupplierStopsCount(alt.supplierStops() == null ? 0 : alt.supplierStops().size());
            opt.setFullyFulfilled(alt.fullyFulfilled());
            opt.setHouseIdsSignature(signatureOf(alt));
            opt.setMapsUrl(alt.mapsUrl());
            opt.setPayloadJson(mapper.convertValue(alt, new TypeReference<>() {}));
            order.addOption(opt);
        }

        CustomerOrder saved = orders.save(order);
        recordEvent(saved, null, "created", null, null);

        Map<String, UUID> optionIds = new LinkedHashMap<>();
        for (OrderRouteOption opt : saved.getOptions()) {
            optionIds.put(opt.getObjective(), opt.getPublicId());
        }
        log.info("history: persisted order publicId={} alternatives={} source={} user={} chat={}",
            saved.getPublicId(), alts.size(), source, appUserId, telegramChatId);
        return new RecordResult(saved.getPublicId(), optionIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionView recordView(UUID orderPublicId, String objective, Integer appUserId) {
        CustomerOrder order = orders.findByPublicId(orderPublicId)
            .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        OrderRouteOption opt = lookupOption(order.getId(), objective);
        Instant now = Instant.now();
        if (opt.getFirstViewedAt() == null) opt.setFirstViewedAt(now);
        opt.setLastViewedAt(now);
        opt.setViewCount(opt.getViewCount() + 1);
        options.save(opt);
        recordEvent(order, opt, "viewed", appUserId, null);
        return new OptionView(opt.getPublicId(), opt.getObjective(), opt.getViewCount());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionView recordChoice(UUID orderPublicId, String objective, Integer appUserId) {
        CustomerOrder order = orders.findByPublicId(orderPublicId)
            .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        OrderRouteOption next = lookupOption(order.getId(), objective);
        OrderRouteOption prev = order.getChosenOption();
        if (prev != null && !prev.getId().equals(next.getId())) {
            Map<String, Object> meta = Map.of("prevObjective", prev.getObjective());
            recordEvent(order, prev, "unchosen", appUserId, meta);
        }
        order.setChosenOption(next);
        order.setChosenAt(Instant.now());
        orders.save(order);
        recordEvent(order, next, "chosen", appUserId, null);
        return new OptionView(next.getPublicId(), next.getObjective(), next.getViewCount());
    }

    @Transactional(readOnly = true)
    public Page<OrderSummaryDto> history(Integer appUserId, boolean adminAll, Pageable pageable) {
        Page<OrderSummaryRow> page = adminAll
            ? orders.findAllSummaries(pageable)
            : orders.findSummariesByAppUser(appUserId, pageable);
        return page.map(this::summaryFromRow);
    }

    @Transactional(readOnly = true)
    public OrderDetailDto detail(UUID orderPublicId, Integer requestingUserId, boolean isAdmin) {
        OrderSummaryRow summaryRow = orders.findSummaryByPublicId(orderPublicId)
            .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        if (!isAdmin) {
            Integer ownerId = summaryRow.getAppUserId();
            if (ownerId == null || !ownerId.equals(requestingUserId)) {
                throw new AccessDeniedSimple();
            }
        }
        OrderSummaryDto summary = summaryFromRow(summaryRow);
        List<OrderOptionDto> opts = orders.findOptionRowsByOrderPublicId(orderPublicId).stream()
            .map(this::optionFromRow)
            .toList();
        List<OrderEventDto> evs = orders.findEventRowsByOrderPublicId(orderPublicId).stream()
            .map(this::eventFromRow)
            .toList();
        return new OrderDetailDto(summary, opts, evs);
    }

    private OrderSummaryDto summaryFromRow(OrderSummaryRow r) {
        return new OrderSummaryDto(
            r.getPublicId(),
            r.getCreatedAt(),
            r.getSource(),
            r.getUsername(),
            r.getTelegramChatId(),
            r.getStartLat(),
            r.getStartLng(),
            r.getStartName(),
            r.getDestinationHouseId(),
            r.getDestinationHouseName(),
            parseJsonMap(r.getMaterialsJson()),
            nullToZero(r.getAlternativesCount()),
            Boolean.TRUE.equals(r.getFullyFulfilled()),
            r.getChosenObjective(),
            r.getChosenAt()
        );
    }

    private OrderOptionDto optionFromRow(OrderOptionRow r) {
        return new OrderOptionDto(
            r.getPublicId(),
            r.getObjective(),
            Boolean.TRUE.equals(r.getIsPrimary()),
            nullToZero(r.getSequenceIndex()),
            nullToZero(r.getTotalDistanceKm()),
            nullToZero(r.getTotalMinutes()),
            nullToZero(r.getTotalStops()),
            nullToZero(r.getSupplierStopsCount()),
            Boolean.TRUE.equals(r.getFullyFulfilled()),
            r.getMapsUrl(),
            r.getFirstViewedAt(),
            r.getLastViewedAt(),
            nullToZero(r.getViewCount()),
            Boolean.TRUE.equals(r.getIsChosen()),
            parseJsonMap(r.getPayloadJson())
        );
    }

    private OrderEventDto eventFromRow(OrderEventRow r) {
        return new OrderEventDto(
            r.getEventType(),
            r.getAt(),
            r.getObjective(),
            r.getUsername(),
            parseJsonMap(r.getMetadataJson())
        );
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("history: failed to parse persisted JSON column — returning null. raw={}", json, e);
            return null;
        }
    }

    private static int nullToZero(Integer v) { return v == null ? 0 : v; }

    public static Integer currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser u) {
            return u.getUserId();
        }
        return null;
    }

    public static boolean currentIsAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private OrderRouteOption lookupOption(Long orderId, String objective) {
        return options.findByOrder_IdAndObjective(orderId, objective)
            .orElseThrow(() -> new EntityNotFoundException("Option not found: " + objective));
    }

    private void recordEvent(CustomerOrder order, OrderRouteOption option, String type,
                             Integer appUserId, Map<String, Object> metadata) {
        OrderEvent ev = new OrderEvent();
        ev.setOrder(order);
        ev.setOption(option);
        ev.setEventType(type);
        ev.setAt(Instant.now());
        if (appUserId != null) {
            Optional<AppUser> u = users.findById(appUserId);
            u.ifPresent(ev::setAppUser);
        }
        ev.setMetadataJson(metadata);
        events.save(ev);
    }

    private String signatureOf(RouteOptionDto alt) {
        StringBuilder sb = new StringBuilder();
        appendStopIds(sb, "h", alt.route());
        appendStopIds(sb, "s", alt.supplierStops());
        return sb.length() == 0 ? "empty" : sb.toString();
    }

    private void appendStopIds(StringBuilder sb, String prefix, List<RouteStopDto> stops) {
        if (stops == null) return;
        for (RouteStopDto s : stops) {
            if (sb.length() > 0) sb.append('-');
            sb.append(prefix).append(s.id());
        }
    }

    private Map<String, Object> toStringKeyMap(Map<String, Object> raw) {
        if (raw == null) return Map.of();
        return raw.entrySet().stream().collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> e.getValue() == null ? "" : e.getValue(),
            (a, b) -> b,
            LinkedHashMap::new));
    }

    public record RecordResult(UUID orderPublicId, Map<String, UUID> optionPublicIds) {}
    public record OptionView(UUID optionPublicId, String objective, int viewCount) {}

    public static class AccessDeniedSimple extends RuntimeException {
        public AccessDeniedSimple() { super("Forbidden"); }
    }

    /** Resolves the destination house's display name without exposing the entity. */
    public Optional<String> destinationName(CustomerOrder order) {
        House h = order.getDestinationHouse();
        return Optional.ofNullable(h).map(House::getName);
    }
}
