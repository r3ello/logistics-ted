package com.bellgado.logistics_ted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.bellgado.logistics_ted.service.OrderHistoryService.RecordResult;
import com.bellgado.logistics_ted.service.OrderHistoryService.Source;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.DeficitDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.LocationDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.StopContributionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for OrderHistoryService. Mocks the repositories so the tests stay free of
 * Postgres (the integration tests in the broader suite do exercise the real schema). The goal is
 * to lock down the mapping rules (primary flag, sequence, signature) and the choose/view side
 * effects (counter increment, event emission, transition events on re-pick).
 */
class OrderHistoryServiceTest {

    private CustomerOrderRepository orders;
    private OrderRouteOptionRepository options;
    private OrderEventRepository events;
    private AppUserRepository users;
    private HouseRepository houses;
    private OrderHistoryService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RoutingProperties props = new RoutingProperties(
        "haversine", null, null, null, null, null);

    @BeforeEach
    void setUp() {
        orders = mock(CustomerOrderRepository.class);
        options = mock(OrderRouteOptionRepository.class);
        events = mock(OrderEventRepository.class);
        users = mock(AppUserRepository.class);
        houses = mock(HouseRepository.class);

        // Assign synthetic ids on save so chosen-option transitions can be compared.
        AtomicLong orderSeq = new AtomicLong(1);
        AtomicLong optSeq = new AtomicLong(100);
        when(orders.save(any(CustomerOrder.class))).thenAnswer(inv -> {
            CustomerOrder o = inv.getArgument(0);
            if (o.getId() == null) o.setId(orderSeq.getAndIncrement());
            for (OrderRouteOption opt : o.getOptions()) {
                if (opt.getId() == null) opt.setId(optSeq.getAndIncrement());
            }
            return o;
        });
        when(events.save(any(OrderEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(options.save(any(OrderRouteOption.class))).thenAnswer(inv -> inv.getArgument(0));

        service = new OrderHistoryService(orders, options, events, users, houses, mapper, props);
    }

    @Test
    void recordCalculationPersistsOrderAndAllAlternatives() {
        OrderRequest req = sampleRequest();
        OrderResponse resp = sampleResponse(3);

        RecordResult rec = service.recordCalculation(req, resp, Source.DASHBOARD, null, null, 42);

        assertThat(rec.orderPublicId()).isNotNull();
        assertThat(rec.optionPublicIds()).containsOnlyKeys("shortest_distance", "fastest_time", "balanced");

        // One save of the order (which cascades to options) + one event ('created').
        verify(orders, times(1)).save(any(CustomerOrder.class));
        verify(events, times(1)).save(any(OrderEvent.class));
    }

    @Test
    void firstAlternativeIsMarkedPrimary() {
        OrderResponse resp = sampleResponse(3);

        service.recordCalculation(sampleRequest(), resp, Source.DASHBOARD, null, null, 1);

        CustomerOrder saved = captureSavedOrder();
        assertThat(saved.getOptions()).hasSize(3);
        assertThat(saved.getOptions().get(0).isPrimary()).isTrue();
        assertThat(saved.getOptions().get(1).isPrimary()).isFalse();
        assertThat(saved.getOptions().get(2).isPrimary()).isFalse();
        // Sequence index = position in the alternatives list.
        assertThat(saved.getOptions().get(0).getSequenceIndex()).isZero();
        assertThat(saved.getOptions().get(2).getSequenceIndex()).isEqualTo(2);
    }

    @Test
    void signatureIncludesHouseAndSupplierIds() {
        OrderResponse resp = sampleResponse(1);

        service.recordCalculation(sampleRequest(), resp, Source.DASHBOARD, null, null, 1);

        CustomerOrder saved = captureSavedOrder();
        String sig = saved.getOptions().get(0).getHouseIdsSignature();
        // Stops in sample: house 7, house 12; supplier 4. Signature concatenates in order.
        assertThat(sig).isEqualTo("h7-h12-s4");
    }

    @Test
    void recordViewIncrementsCounterAndEmitsEvent() {
        CustomerOrder order = persistedOrderWithOptions("shortest_distance", "fastest_time");
        when(orders.findByPublicId(order.getPublicId())).thenReturn(Optional.of(order));
        when(options.findByOrder_IdAndObjective(order.getId(), "fastest_time"))
            .thenReturn(Optional.of(order.getOptions().get(1)));

        var view = service.recordView(order.getPublicId(), "fastest_time", null);

        OrderRouteOption opt = order.getOptions().get(1);
        assertThat(opt.getViewCount()).isEqualTo(1);
        assertThat(opt.getFirstViewedAt()).isNotNull();
        assertThat(opt.getLastViewedAt()).isNotNull();
        assertThat(view.viewCount()).isEqualTo(1);
        verify(options, times(1)).save(opt);
        verify(events, times(1)).save(any(OrderEvent.class));
    }

    @Test
    void chooseTransitionEmitsUnchosenThenChosenEvents() {
        CustomerOrder order = persistedOrderWithOptions("shortest_distance", "fastest_time");
        when(orders.findByPublicId(order.getPublicId())).thenReturn(Optional.of(order));
        when(options.findByOrder_IdAndObjective(order.getId(), "shortest_distance"))
            .thenReturn(Optional.of(order.getOptions().get(0)));
        when(options.findByOrder_IdAndObjective(order.getId(), "fastest_time"))
            .thenReturn(Optional.of(order.getOptions().get(1)));

        service.recordChoice(order.getPublicId(), "shortest_distance", null);
        assertThat(order.getChosenOption().getObjective()).isEqualTo("shortest_distance");

        service.recordChoice(order.getPublicId(), "fastest_time", null);
        assertThat(order.getChosenOption().getObjective()).isEqualTo("fastest_time");
        assertThat(order.getChosenAt()).isNotNull();

        // First choose -> 1 chosen event. Second choose -> 1 unchosen + 1 chosen = 3 total.
        verify(events, times(3)).save(any(OrderEvent.class));
    }

    private CustomerOrder captureSavedOrder() {
        org.mockito.ArgumentCaptor<CustomerOrder> cap = org.mockito.ArgumentCaptor.forClass(CustomerOrder.class);
        verify(orders).save(cap.capture());
        return cap.getValue();
    }

    private CustomerOrder persistedOrderWithOptions(String... objectives) {
        CustomerOrder o = new CustomerOrder();
        o.setId(99L);
        o.setPublicId(UUID.randomUUID());
        AtomicLong seq = new AtomicLong(500);
        for (int i = 0; i < objectives.length; i++) {
            OrderRouteOption opt = new OrderRouteOption();
            opt.setId(seq.getAndIncrement());
            opt.setPublicId(UUID.randomUUID());
            opt.setObjective(objectives[i]);
            opt.setSequenceIndex(i);
            opt.setPrimary(i == 0);
            o.addOption(opt);
        }
        return o;
    }

    private OrderRequest sampleRequest() {
        Map<String, Object> mats = new LinkedHashMap<>();
        mats.put("1", 150);
        return new OrderRequest(42.7, 23.3, "Driver", 25, mats, "en");
    }

    private OrderResponse sampleResponse(int altCount) {
        List<RouteStopDto> route = List.of(stop(7, "House A"), stop(12, "House B"));
        List<RouteStopDto> supplierStops = List.of(stop(4, "Supplier X"));
        List<DeficitDto> deficit = List.of();

        RouteOptionDto base = new RouteOptionDto(
            "shortest_distance", route, supplierStops, deficit,
            "https://maps.google.com/x", true, 2, 87, 95);
        List<RouteOptionDto> alts = new ArrayList<>();
        alts.add(base);
        if (altCount >= 2) alts.add(new RouteOptionDto(
            "fastest_time", route, supplierStops, deficit,
            "https://maps.google.com/x", true, 2, 92, 90));
        if (altCount >= 3) alts.add(new RouteOptionDto(
            "balanced", route, supplierStops, deficit,
            "https://maps.google.com/x", true, 2, 89, 93));

        return new OrderResponse(
            new LocationDto("gps", "Driver", "42.7, 23.3"),
            new LocationDto(25, "Dest House", "Sofia"),
            route, supplierStops, deficit,
            "https://maps.google.com/x", true, 2, 87, 95, alts);
    }

    private RouteStopDto stop(int id, String name) {
        Map<Integer, StopContributionDto> c = new HashMap<>();
        c.put(1, new StopContributionDto(50, "Plywood", "m2", "optimal_multi", 12, 200));
        return new RouteStopDto(id, name, name + " location", 42.0, 23.0, c);
    }

    @SuppressWarnings("unused")
    private House houseRef(int id, String name) {
        House h = new House();
        h.setId(id);
        h.setName(name);
        h.setLat(BigDecimal.valueOf(42.0));
        h.setLng(BigDecimal.valueOf(23.0));
        return h;
    }

    @SuppressWarnings("unused")
    private AppUser userRef(int id) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("user" + id);
        u.setRole("user");
        return u;
    }
}
