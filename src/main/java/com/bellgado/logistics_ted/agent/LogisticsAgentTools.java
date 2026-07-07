package com.bellgado.logistics_ted.agent;

import com.bellgado.logistics_ted.domain.Crew;
import com.bellgado.logistics_ted.domain.Depot;
import com.bellgado.logistics_ted.domain.DepotInventory;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Scaffold;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.domain.Supplier;
import com.bellgado.logistics_ted.domain.SupplierInventory;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.domain.Worker;
import com.bellgado.logistics_ted.domain.WorkerRole;
import com.bellgado.logistics_ted.repository.CrewRepository;
import com.bellgado.logistics_ted.repository.DepotInventoryRepository;
import com.bellgado.logistics_ted.repository.DepotRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.config.RoutingProperties;
import com.bellgado.logistics_ted.repository.ScaffoldRepository;
import com.bellgado.logistics_ted.repository.SupplierInventoryRepository;
import com.bellgado.logistics_ted.repository.SupplierRepository;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import com.bellgado.logistics_ted.repository.WorkerRepository;
import com.bellgado.logistics_ted.service.OrderHistoryService;
import com.bellgado.logistics_ted.service.OrderHistoryService.RecordResult;
import com.bellgado.logistics_ted.service.OrderHistoryService.Source;
import com.bellgado.logistics_ted.service.RouteOptimizationService;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderDetailDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderEventDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderHistoryDtos.OrderSummaryDto;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.DeficitDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.StopContributionDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class LogisticsAgentTools {

    private final HouseRepository houses;
    private final MaterialRepository materials;
    private final InventoryRepository inventories;
    private final WarehouseRepository warehouses;
    private final CrewRepository crews;
    private final WorkerRepository workers;
    private final ScaffoldRepository scaffolds;
    private final SupplierRepository suppliers;
    private final SupplierInventoryRepository supplierInventories;
    private final DepotRepository depots;
    private final DepotInventoryRepository depotInventories;
    private final RouteOptimizationService routeOptimization;
    private final RoutingProperties routingProperties;
    private final ObjectMapper objectMapper;
    private final OrderHistoryService orderHistory;

    // ========================================================================
    // LOOKUP TOOLS
    // ========================================================================

    @Tool(description = "List all houses in the system with id, name, location text, GPS coordinates, " +
            "start date and current construction phase. Use this to resolve a house name to its numeric id " +
            "before calling calculateOrder, or to show the user which houses exist.")
    @Transactional(readOnly = true)
    public String listHouses() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (House h : houses.findAllByOrderByIdAsc()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", h.getId());
                row.put("name", h.getName());
                row.put("location", h.getLocation());
                row.put("lat", h.getLat());
                row.put("lng", h.getLng());
                row.put("startDate", h.getStartDate() == null ? null : h.getStartDate().toString());
                row.put("currentPhase", h.getCurrentPhase());
                out.add(row);
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("listHouses failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "List all materials in the catalog with id, name, unit (e.g. 'm2', 'kg', 'units') " +
            "and unit price. Use this to resolve a material name (e.g. 'plywood') to its numeric id before " +
            "calling calculateOrder. The user might type the material name in any language — match on partial / " +
            "fuzzy name comparison after retrieving the catalog.")
    @Transactional(readOnly = true)
    public String listMaterials() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Material m : materials.findAllByOrderByIdAsc()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", m.getId());
                row.put("name", m.getName());
                row.put("unit", m.getUnit());
                row.put("price", m.getPrice());
                out.add(row);
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("listMaterials failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "Get the current inventory (materials and quantities) stored at a specific house's " +
            "warehouse. Returns an empty list if the house has no warehouse or no stock. Useful to check " +
            "whether a house has enough of a material before suggesting it as a source.")
    @Transactional(readOnly = true)
    public String getHouseInventory(
            @ToolParam(description = "House numeric id") String houseId) {
        try {
            int hid = Integer.parseInt(houseId);
            Optional<Warehouse> w = warehouses.findByHouseId(hid);
            if (w.isEmpty()) {
                return objectMapper.writeValueAsString(Map.of(
                    "houseId", hid, "inventory", List.of()));
            }
            List<Inventory> rows = inventories.findAllWithJoins().stream()
                .filter(i -> i.getWarehouse().getId().equals(w.get().getId()))
                .toList();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Inventory inv : rows) {
                Material m = inv.getMaterial();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("materialId", m.getId());
                row.put("name", m.getName());
                row.put("unit", m.getUnit());
                row.put("quantity", inv.getQuantity());
                items.add(row);
            }
            return objectMapper.writeValueAsString(Map.of(
                "houseId", hid, "inventory", items));
        } catch (Exception e) {
            log.warn("getHouseInventory failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "Get global stock totals across all houses — for each material, the total quantity " +
            "available system-wide and its total value. Useful for a quick 'do we have enough of X anywhere?' " +
            "check before planning a route.")
    @Transactional(readOnly = true)
    public String getMaterialTotals() {
        try {
            Map<Integer, Map<String, Object>> agg = new LinkedHashMap<>();
            for (Material m : materials.findAllByOrderByIdAsc()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("materialId", m.getId());
                row.put("name", m.getName());
                row.put("unit", m.getUnit());
                row.put("totalQuantity", BigDecimal.ZERO);
                row.put("totalValue", BigDecimal.ZERO);
                agg.put(m.getId(), row);
            }
            for (Inventory inv : inventories.findAllWithJoins()) {
                Material m = inv.getMaterial();
                Map<String, Object> row = agg.get(m.getId());
                if (row == null) continue;
                BigDecimal totalQty = ((BigDecimal) row.get("totalQuantity")).add(inv.getQuantity());
                BigDecimal totalVal = ((BigDecimal) row.get("totalValue"))
                    .add(inv.getQuantity().multiply(m.getPrice()));
                row.put("totalQuantity", totalQty);
                row.put("totalValue", totalVal);
            }
            return objectMapper.writeValueAsString(new ArrayList<>(agg.values()));
        } catch (Exception e) {
            log.warn("getMaterialTotals failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // MAIN FLOW
    // ========================================================================

    @Tool(description = "Calculate the optimal delivery route for materials needed at a destination house. " +
            "This is the main operation — given a destination house and a list of required materials, the " +
            "system picks which houses to visit (and in what order) to collect the materials with the " +
            "shortest km / fastest time / balanced trade-off, and falls back to suppliers if houses cannot " +
            "cover the demand. " +
            "REQUIRED inputs: destinationHouseId, materialsJson, and an origin — either originHouseId OR " +
            "BOTH originLat and originLng. If no origin is given, ask the user where the truck is starting " +
            "from (a house name or GPS coordinates) instead of guessing. " +
            "materialsJson must be a JSON object mapping material id (as string) to quantity, e.g. " +
            "'{\"4\": 150, \"7\": 20}'. Use listMaterials to look up material ids by name first.")
    public String calculateOrder(
            @ToolParam(description = "Destination house numeric id (where the materials need to arrive)")
            String destinationHouseId,
            @ToolParam(description = "JSON object mapping material id (string) to quantity, e.g. '{\"4\": 150}'")
            String materialsJson,
            @ToolParam(description = "Origin house id — the truck starts at this house's coordinates. " +
                    "Pass empty string if originLat/originLng are provided instead.")
            String originHouseId,
            @ToolParam(description = "Origin latitude (decimal degrees). Empty string if originHouseId is provided.")
            String originLat,
            @ToolParam(description = "Origin longitude (decimal degrees). Empty string if originHouseId is provided.")
            String originLng,
            @ToolParam(description = "Optional human-friendly name for the origin (e.g. 'Driver location' " +
                    "or 'Warehouse Sofia'). Empty string for default.")
            String originName) {
        try {
            Integer destId = Integer.parseInt(destinationHouseId);

            Map<String, Object> materialsMap = objectMapper.readValue(
                materialsJson, new TypeReference<>() {});
            if (materialsMap.isEmpty()) {
                return "Error: materialsJson is empty — provide at least one {materialId: quantity} entry.";
            }

            Double startLat = null;
            Double startLng = null;
            String startName = originName == null || originName.isBlank() ? null : originName.trim();

            boolean hasGps = originLat != null && !originLat.isBlank()
                && originLng != null && !originLng.isBlank();
            boolean hasOriginHouse = originHouseId != null && !originHouseId.isBlank();

            if (hasGps) {
                startLat = Double.parseDouble(originLat);
                startLng = Double.parseDouble(originLng);
            } else if (hasOriginHouse) {
                Integer originId = Integer.parseInt(originHouseId);
                House origin = houses.findById(originId).orElse(null);
                if (origin == null) {
                    return "Error: origin house id " + originId + " not found.";
                }
                if (origin.getLat() == null || origin.getLng() == null) {
                    return "Error: origin house '" + origin.getName() + "' has no GPS coordinates set.";
                }
                startLat = origin.getLat().doubleValue();
                startLng = origin.getLng().doubleValue();
                if (startName == null) startName = origin.getName();
            } else {
                return "Error: no origin provided. Ask the user where the truck starts from — either a " +
                    "house name (then call listHouses to get its id and pass it as originHouseId) or " +
                    "GPS coordinates (pass them as originLat and originLng).";
            }

            OrderRequest req = new OrderRequest(
                startLat, startLng, startName, destId, materialsMap, "en");
            long t0 = System.currentTimeMillis();
            OrderResponse resp = routeOptimization.calculate(req);
            long elapsed = System.currentTimeMillis() - t0;

            OrderResponse decorated = persistFromAgent(req, resp, elapsed);
            return formatOrderResponse(decorated);
        } catch (RouteOptimizationService.OrderValidationException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.warn("calculateOrder failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "Record the user's final route choice for a previously calculated order. " +
            "Use this after the user explicitly picks one of the alternatives presented earlier " +
            "(e.g. 'use the balanced one', 'go with route 2'). Pass the orderId surfaced by " +
            "calculateOrder and the objective label of the chosen alternative " +
            "(shortest_distance, fastest_time, or balanced).")
    public String chooseOrderRoute(
            @ToolParam(description = "Order public id (UUID string) printed by calculateOrder")
            String orderId,
            @ToolParam(description = "Objective label of the chosen alternative: " +
                    "shortest_distance, fastest_time, or balanced")
            String objective) {
        try {
            UUID id = UUID.fromString(orderId.trim());
            orderHistory.recordChoice(id, objective.trim(), null);
            return "Recorded choice: order " + id + " → " + objective + ".";
        } catch (IllegalArgumentException e) {
            return "Error: orderId must be a UUID string (got: " + orderId + ").";
        } catch (Exception e) {
            log.warn("chooseOrderRoute failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // ORDER HISTORY (read-only — never bumps view counters or chosen state)
    // ========================================================================

    @Tool(description = "List previously calculated orders (route calculations), newest first. " +
            "By default returns only orders created from this chat; pass scope='all' to also see " +
            "orders created from the web dashboard. Read-only — browsing history never modifies it. " +
            "Use getOrderDetails for the full route of a specific order.")
    public String listOrders(
            @ToolParam(description = "'mine' (default) for this chat's orders, 'all' for every order " +
                    "including dashboard ones. Empty string for default.")
            String scope,
            @ToolParam(description = "Max number of orders to return, 1-50. Empty string for default 10.")
            String limit) {
        try {
            int lim = 10;
            if (limit != null && !limit.isBlank()) {
                lim = Math.min(50, Math.max(1, Integer.parseInt(limit.trim())));
            }
            boolean allSources = "all".equalsIgnoreCase(scope == null ? "" : scope.trim());
            Long chatId = AgentContext.getTelegramChatId();
            List<OrderSummaryDto> page = orderHistory
                .historyForAgent(chatId, allSources, PageRequest.of(0, lim))
                .getContent();
            if (page.isEmpty()) {
                return allSources ? "No orders recorded yet."
                    : "No orders from this chat yet. Try scope='all' to include dashboard orders.";
            }
            Map<Integer, Material> matIdx = materialIndex();
            List<Map<String, Object>> out = new ArrayList<>();
            for (OrderSummaryDto s : page) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("orderId", s.orderId());
                row.put("createdAt", s.createdAt());
                row.put("source", s.source());
                row.put("origin", s.startName());
                row.put("destinationHouseId", s.destinationHouseId());
                row.put("destinationHouse", s.destinationHouseName());
                row.put("materials", resolveMaterials(s.materials(), matIdx));
                row.put("alternativesCount", s.alternativesCount());
                row.put("fullyFulfilled", s.fullyFulfilled());
                row.put("chosenObjective", s.chosenObjective());
                out.add(row);
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("listOrders failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "Get the full detail of a previously calculated order: request snapshot, all " +
            "route alternatives with km/min, which one was chosen, the stops of the chosen (or primary) " +
            "route, and the event timeline. Read-only — it never recalculates the route or alters the " +
            "order. Use this when the user asks to see a past order or route again.")
    public String getOrderDetails(
            @ToolParam(description = "Order public id (UUID string) from listOrders or calculateOrder")
            String orderId) {
        try {
            UUID id = UUID.fromString(orderId.trim());
            OrderDetailDto d = orderHistory.detailForAgent(id);
            return formatOrderDetail(d);
        } catch (IllegalArgumentException e) {
            return "Error: orderId must be a UUID string (got: " + orderId + ").";
        } catch (EntityNotFoundException e) {
            return "Error: order not found: " + orderId;
        } catch (Exception e) {
            log.warn("getOrderDetails failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // SUPPLIERS
    // ========================================================================

    @Tool(description = "List external suppliers and what they stock. Pass a material id to get only " +
            "the suppliers stocking that material (with quantity and unit price); empty string lists " +
            "every supplier with their full stock. Useful for 'who sells X?' questions before planning " +
            "a route — the route optimizer falls back to these suppliers automatically when houses " +
            "cannot cover the demand.")
    @Transactional(readOnly = true)
    public String listSuppliers(
            @ToolParam(description = "Material numeric id to filter by, or empty string for all suppliers")
            String materialId) {
        try {
            Map<Integer, Map<String, Object>> bySupplier = new LinkedHashMap<>();
            List<SupplierInventory> rows;
            if (materialId == null || materialId.isBlank()) {
                for (Supplier s : suppliers.findAll()) {
                    bySupplier.put(s.getId(), supplierJson(s));
                }
                rows = supplierInventories.findAllStocked();
            } else {
                int mid = Integer.parseInt(materialId.trim());
                rows = supplierInventories.findStocked(List.of(mid));
            }
            for (SupplierInventory si : rows) {
                Map<String, Object> sup = bySupplier.computeIfAbsent(
                    si.getSupplier().getId(), k -> supplierJson(si.getSupplier()));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mats = (List<Map<String, Object>>) sup.get("materials");
                Map<String, Object> mat = new LinkedHashMap<>();
                mat.put("materialId", si.getMaterial().getId());
                mat.put("name", si.getMaterial().getName());
                mat.put("unit", si.getMaterial().getUnit());
                mat.put("quantity", si.getQuantity());
                mat.put("unitPrice", si.getUnitPrice());
                mats.add(mat);
            }
            if (bySupplier.isEmpty()) {
                return "No supplier stocks material id " + materialId.trim() + ".";
            }
            return objectMapper.writeValueAsString(new ArrayList<>(bySupplier.values()));
        } catch (Exception e) {
            log.warn("listSuppliers failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "List company warehouses (depots) and what they stock. Pass a material id to get " +
            "only the warehouses stocking that material (with quantity); empty string lists every " +
            "warehouse with its full stock. Warehouses are the tier-2 source: the route optimizer pulls " +
            "from them after houses and before external suppliers.")
    @Transactional(readOnly = true)
    public String listWarehouses(
            @ToolParam(description = "Material numeric id to filter by, or empty string for all warehouses")
            String materialId) {
        try {
            Map<Integer, Map<String, Object>> byDepot = new LinkedHashMap<>();
            List<DepotInventory> rows;
            if (materialId == null || materialId.isBlank()) {
                for (Depot d : depots.findAll()) {
                    byDepot.put(d.getId(), depotJson(d));
                }
                rows = depotInventories.findAllStocked();
            } else {
                int mid = Integer.parseInt(materialId.trim());
                rows = depotInventories.findStocked(List.of(mid));
            }
            for (DepotInventory di : rows) {
                Map<String, Object> dep = byDepot.computeIfAbsent(
                    di.getDepot().getId(), k -> depotJson(di.getDepot()));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> mats = (List<Map<String, Object>>) dep.get("materials");
                Map<String, Object> mat = new LinkedHashMap<>();
                mat.put("materialId", di.getMaterial().getId());
                mat.put("name", di.getMaterial().getName());
                mat.put("unit", di.getMaterial().getUnit());
                mat.put("quantity", di.getQuantity());
                mats.add(mat);
            }
            if (byDepot.isEmpty()) {
                return materialId == null || materialId.isBlank()
                    ? "No warehouses defined yet."
                    : "No warehouse stocks material id " + materialId.trim() + ".";
            }
            return objectMapper.writeValueAsString(new ArrayList<>(byDepot.values()));
        } catch (Exception e) {
            log.warn("listWarehouses failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // CREWS / WORKERS
    // ========================================================================

    @Tool(description = "List all work crews with id, name, manager, leader, member count and the house " +
            "they are currently assigned to. Use getCrewDetails for the full member roster of one crew.")
    @Transactional(readOnly = true)
    public String listCrews() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Crew c : crews.findAll()) {
                out.add(crewJson(c, false));
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("listCrews failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "Get one crew's full roster: manager, leader, and every member with their trade, " +
            "plus the house the crew is assigned to.")
    @Transactional(readOnly = true)
    public String getCrewDetails(
            @ToolParam(description = "Crew numeric id") String crewId) {
        try {
            int cid = Integer.parseInt(crewId.trim());
            Optional<Crew> c = crews.findById(cid);
            if (c.isEmpty()) {
                return "Error: crew not found: " + cid;
            }
            return objectMapper.writeValueAsString(crewJson(c.get(), true));
        } catch (NumberFormatException e) {
            return "Error: crewId must be a number (got: " + crewId + ").";
        } catch (Exception e) {
            log.warn("getCrewDetails failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "List workers with their role (CREW_MANAGER, CREW_LEADER, CREW_MEMBER), trade, " +
            "crew assignment and house. Pass a name fragment to filter (case-insensitive), or empty " +
            "string for everyone. Answers questions like 'which crew is Ivan in?'.")
    @Transactional(readOnly = true)
    public String listWorkers(
            @ToolParam(description = "Name fragment to filter by, or empty string for all workers")
            String nameFilter) {
        try {
            String needle = nameFilter == null ? "" : nameFilter.trim().toLowerCase(Locale.ROOT);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Worker w : workers.findAll()) {
                if (!needle.isEmpty()
                        && (w.getName() == null || !w.getName().toLowerCase(Locale.ROOT).contains(needle))) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", w.getId());
                row.put("name", w.getName());
                row.put("role", w.getRole());
                row.put("trade", w.getTrade());
                row.put("crewId", w.getCrew() != null ? w.getCrew().getId() : null);
                row.put("crewName", w.getCrew() != null ? w.getCrew().getName() : null);
                row.put("houseId",   w.getCrew() != null && w.getCrew().getHouse() != null ? w.getCrew().getHouse().getId()   : null);
                row.put("houseName", w.getCrew() != null && w.getCrew().getHouse() != null ? w.getCrew().getHouse().getName() : null);
                out.add(row);
            }
            if (out.isEmpty() && !needle.isEmpty()) {
                return "No worker matches '" + nameFilter.trim() + "'.";
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("listWorkers failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // SCAFFOLDS
    // ========================================================================

    @Tool(description = "List all scaffolds with status (e.g. AVAILABLE, IN_USE), rental start/end dates " +
            "and the house each one is assigned to.")
    @Transactional(readOnly = true)
    public String listScaffolds() {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Scaffold s : scaffolds.findAll()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", s.getId());
                row.put("status", s.getStatus());
                row.put("startDate", s.getStartDate() != null ? s.getStartDate().toString() : null);
                row.put("endDate", s.getEndDate() != null ? s.getEndDate().toString() : null);
                row.put("houseId", s.getHouse() != null ? s.getHouse().getId() : null);
                row.put("houseName", s.getHouse() != null ? s.getHouse().getName() : null);
                out.add(row);
            }
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            log.warn("listScaffolds failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    @Tool(description = "Find the closest house with an AVAILABLE scaffold that could be moved to a " +
            "destination house. Reports the source house, the distance in km and a Google Maps link, " +
            "or that the destination already has a scaffold on site. Read-only — it only suggests the " +
            "move, it does not reassign the scaffold.")
    @Transactional(readOnly = true)
    public String findClosestScaffold(
            @ToolParam(description = "Destination house numeric id (where the scaffold is needed)")
            String destinationHouseId) {
        try {
            int destId = Integer.parseInt(destinationHouseId.trim());
            House dest = houses.findById(destId).orElse(null);
            if (dest == null) {
                return "Error: house not found: " + destId;
            }
            if (dest.getLat() == null || dest.getLng() == null) {
                return "Error: house '" + dest.getName() + "' has no GPS coordinates set.";
            }
            if (dest.getScaffoldStatus() == ScaffoldStatus.AVAILABLE) {
                return "House '" + dest.getName() + "' already has an available scaffold on site — no transport needed.";
            }
            List<House> available = houses
                .findByScaffoldStatusAndLatIsNotNullAndLngIsNotNull(ScaffoldStatus.AVAILABLE)
                .stream()
                .filter(h -> !h.getId().equals(dest.getId()))
                .toList();
            if (available.isEmpty()) {
                return "No house currently has an available scaffold to move.";
            }
            double destLat = dest.getLat().doubleValue();
            double destLng = dest.getLng().doubleValue();
            House closest = available.stream()
                .min(Comparator.comparingDouble(h -> haversineKm(
                    destLat, destLng, h.getLat().doubleValue(), h.getLng().doubleValue())))
                .orElseThrow();
            long distKm = Math.round(haversineKm(
                destLat, destLng, closest.getLat().doubleValue(), closest.getLng().doubleValue()));
            String mapsUrl = "https://www.google.com/maps/dir/"
                + closest.getLat() + "," + closest.getLng() + "/"
                + dest.getLat() + "," + dest.getLng();
            return "Closest available scaffold: house '" + closest.getName() + "' [house " + closest.getId()
                + "] — " + closest.getLocation() + ", ~" + distKm + " km from '" + dest.getName() + "'.\n"
                + "Google Maps: " + mapsUrl;
        } catch (NumberFormatException e) {
            return "Error: destinationHouseId must be a number (got: " + destinationHouseId + ").";
        } catch (Exception e) {
            log.warn("findClosestScaffold failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    /**
     * Persist the calculation as a Telegram-sourced order and decorate the response with the
     * resulting orderId / optionIds so {@link #formatOrderResponse} can surface them. History
     * failures degrade gracefully — the user still gets a valid route summary.
     */
    private OrderResponse persistFromAgent(OrderRequest req, OrderResponse resp, long elapsedMs) {
        try {
            Long chatId = AgentContext.getTelegramChatId();
            Source source = chatId != null ? Source.TELEGRAM : Source.API;
            RecordResult rec = orderHistory.recordCalculation(req, resp, source, null, chatId, elapsedMs);
            List<RouteOptionDto> withIds = new ArrayList<>();
            for (RouteOptionDto alt : resp.alternatives()) {
                UUID optId = rec.optionPublicIds().get(alt.objective());
                withIds.add(optId == null ? alt : alt.withOptionId(optId));
            }
            return resp.withIds(rec.orderPublicId(), withIds);
        } catch (RuntimeException ex) {
            log.warn("agent: history persist failed — returning result without IDs", ex);
            return resp;
        }
    }

    /**
     * Compact, LLM-friendly text rendering of the order response. We avoid dumping the full JSON because
     * the route DTO is verbose and the LLM tends to over-quote it back to the user. This summary
     * preserves the essentials: origin, destination, totals, every stop with material contributions,
     * supplier fallback stops, any deficits, the Google Maps link, and the per-objective alternatives.
     */
    private String formatOrderResponse(OrderResponse resp) {
        StringBuilder sb = new StringBuilder();
        sb.append("ROUTE RESULT\n");
        if (resp.orderId() != null) {
            sb.append("OrderId: ").append(resp.orderId())
                .append("  (pass this to chooseOrderRoute when the user picks an alternative)\n");
        }
        sb.append("Origin: ").append(resp.origin().name()).append(" (")
            .append(resp.origin().location()).append(")\n");
        sb.append("Destination: ").append(resp.destination().name()).append(" (")
            .append(resp.destination().location()).append(")\n");
        sb.append("Total distance: ").append(resp.totalDistance()).append(" km\n");
        sb.append("Total time: ").append(resp.totalMinutes()).append(" min\n");
        double litres = resp.totalDistance() * routingProperties.fuel().consumptionLPer100km() / 100.0;
        double eur = litres * routingProperties.fuel().pricePerLitreEur();
        sb.append(String.format(Locale.ROOT, "Fuel estimate: %.1f L, ~€%.2f%n", litres, eur));
        sb.append("House stops: ").append(resp.totalStops()).append("\n");
        sb.append("Fully fulfilled: ").append(resp.fullyFulfilled()).append("\n");

        if (resp.route() != null && !resp.route().isEmpty()) {
            sb.append("\nHouse stops:\n");
            int i = 1;
            for (RouteStopDto stop : resp.route()) {
                sb.append("  ").append(i++).append(". [house ").append(stop.id()).append("] ")
                    .append(stop.name()).append(" — ").append(stop.location()).append("\n");
                for (StopContributionDto c : stop.contribution().values()) {
                    sb.append("       - ").append(formatQty(c.quantity())).append(" ").append(c.unit())
                        .append(" of ").append(c.name())
                        .append(" (available: ").append(formatQty(c.availableQty())).append(" ").append(c.unit())
                        .append(", ").append(c.distanceFromOrigin()).append(" km from origin)\n");
                }
            }
        } else {
            sb.append("\nNo house stops selected (no candidate house had any of the requested materials).\n");
        }

        if (resp.warehouseStops() != null && !resp.warehouseStops().isEmpty()) {
            sb.append("\nWarehouse stops (company depots, used before suppliers):\n");
            int i = 1;
            for (RouteStopDto stop : resp.warehouseStops()) {
                sb.append("  ").append(i++).append(". [warehouse ").append(stop.id()).append("] ")
                    .append(stop.name()).append(" — ").append(stop.location()).append("\n");
                for (StopContributionDto c : stop.contribution().values()) {
                    sb.append("       - ").append(formatQty(c.quantity())).append(" ").append(c.unit())
                        .append(" of ").append(c.name()).append("\n");
                }
            }
        }

        if (resp.supplierStops() != null && !resp.supplierStops().isEmpty()) {
            sb.append("\nSupplier stops (external, fallback for what houses + warehouses couldn't cover):\n");
            int i = 1;
            for (RouteStopDto stop : resp.supplierStops()) {
                sb.append("  ").append(i++).append(". [supplier ").append(stop.id()).append("] ")
                    .append(stop.name()).append(" — ").append(stop.location()).append("\n");
                for (StopContributionDto c : stop.contribution().values()) {
                    sb.append("       - ").append(formatQty(c.quantity())).append(" ").append(c.unit())
                        .append(" of ").append(c.name()).append("\n");
                }
            }
        }

        if (resp.deficit() != null && !resp.deficit().isEmpty()) {
            sb.append("\nUnfulfilled (no stock anywhere):\n");
            for (DeficitDto d : resp.deficit()) {
                sb.append("  - ").append(formatQty(d.quantity())).append(" ").append(d.unit())
                    .append(" of ").append(d.name()).append("\n");
            }
        }

        if (resp.alternatives() != null && resp.alternatives().size() > 1) {
            sb.append("\nAlternatives by objective:\n");
            for (RouteOptionDto opt : resp.alternatives()) {
                sb.append("  - ").append(opt.objective())
                    .append(": ").append(opt.totalDistance()).append(" km, ")
                    .append(opt.totalMinutes()).append(" min, ")
                    .append(opt.route().size()).append(" house stop(s)");
                if (opt.warehouseStops() != null && !opt.warehouseStops().isEmpty()) {
                    sb.append(", ").append(opt.warehouseStops().size()).append(" warehouse stop(s)");
                }
                if (!opt.supplierStops().isEmpty()) {
                    sb.append(", ").append(opt.supplierStops().size()).append(" supplier stop(s)");
                }
                sb.append("\n");
            }
        }

        if (resp.mapsUrl() != null) {
            sb.append("\nGoogle Maps: ").append(resp.mapsUrl()).append("\n");
        }
        return sb.toString();
    }

    private Map<Integer, Material> materialIndex() {
        Map<Integer, Material> idx = new HashMap<>();
        for (Material m : materials.findAllByOrderByIdAsc()) {
            idx.put(m.getId(), m);
        }
        return idx;
    }

    /** Turns the persisted {materialId: quantity} request map into LLM-readable entries with names. */
    private List<Map<String, Object>> resolveMaterials(Map<String, Object> raw, Map<Integer, Material> matIdx) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", e.getKey());
            try {
                Material m = matIdx.get(Integer.parseInt(e.getKey()));
                if (m != null) {
                    row.put("name", m.getName());
                    row.put("unit", m.getUnit());
                }
            } catch (NumberFormatException ignored) {
                // non-numeric key in persisted JSON — surface it as-is
            }
            row.put("quantity", e.getValue());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> supplierJson(Supplier s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("name", s.getName());
        row.put("location", s.getLocation());
        row.put("materials", new ArrayList<Map<String, Object>>());
        return row;
    }

    private static Map<String, Object> depotJson(Depot d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", d.getId());
        row.put("name", d.getName());
        row.put("location", d.getLocation());
        row.put("materials", new ArrayList<Map<String, Object>>());
        return row;
    }

    private Map<String, Object> crewJson(Crew c, boolean includeMembers) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("name", c.getName());
        row.put("managerId", c.getManager() != null ? c.getManager().getId() : null);
        row.put("managerName", c.getManager() != null ? c.getManager().getName() : null);
        List<Worker> all = workers.findByCrewId(c.getId());
        Worker leader = all.stream().filter(w -> w.getRole() == WorkerRole.CREW_LEADER).findFirst().orElse(null);
        row.put("leaderId", leader != null ? leader.getId() : null);
        row.put("leaderName", leader != null ? leader.getName() : null);
        List<Worker> members = all.stream().filter(w -> w.getRole() == WorkerRole.CREW_MEMBER).toList();
        row.put("memberCount", members.size());
        if (includeMembers) {
            List<Map<String, Object>> memberRows = new ArrayList<>();
            for (Worker w : members) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("id", w.getId());
                mm.put("name", w.getName());
                mm.put("trade", w.getTrade());
                memberRows.add(mm);
            }
            row.put("members", memberRows);
        }
        row.put("houseId", c.getHouse() != null ? c.getHouse().getId() : null);
        row.put("houseName", c.getHouse() != null ? c.getHouse().getName() : null);
        return row;
    }

    /**
     * Compact text rendering of a historical order. Mirrors the spirit of {@link #formatOrderResponse}:
     * header facts, the alternatives table, the chosen (or primary) option's stops re-read from the
     * persisted payload JSON, and the event timeline. Payload traversal is defensive because the
     * persisted shape is a generic Map, not the live DTO.
     */
    private String formatOrderDetail(OrderDetailDto d) {
        OrderSummaryDto s = d.summary();
        StringBuilder sb = new StringBuilder();
        sb.append("ORDER ").append(s.orderId()).append("\n");
        sb.append("Created: ").append(s.createdAt()).append("  Source: ").append(s.source());
        if (s.username() != null) sb.append("  User: ").append(s.username());
        sb.append("\n");
        sb.append("Origin: ").append(s.startName() != null ? s.startName()
            : s.startLat() + "," + s.startLng()).append("\n");
        sb.append("Destination: ").append(s.destinationHouseName())
            .append(" [house ").append(s.destinationHouseId()).append("]\n");

        Map<Integer, Material> matIdx = materialIndex();
        sb.append("Requested:\n");
        for (Map<String, Object> m : resolveMaterials(s.materials(), matIdx)) {
            sb.append("  - ").append(m.get("quantity"));
            if (m.get("unit") != null) sb.append(" ").append(m.get("unit"));
            sb.append(" of ").append(m.get("name") != null ? m.get("name") : "material id " + m.get("materialId"))
                .append("\n");
        }
        sb.append("Fully fulfilled: ").append(s.fullyFulfilled()).append("\n");
        sb.append("Chosen: ").append(s.chosenObjective() != null
            ? s.chosenObjective() + " (at " + s.chosenAt() + ")" : "none yet").append("\n");

        OrderOptionDto render = null;
        if (d.options() != null && !d.options().isEmpty()) {
            sb.append("\nAlternatives:\n");
            for (OrderOptionDto o : d.options()) {
                sb.append("  - ").append(o.objective())
                    .append(": ").append(o.totalDistanceKm()).append(" km, ")
                    .append(o.totalMinutes()).append(" min, ")
                    .append(o.totalStops()).append(" house stop(s)");
                if (o.warehouseStopsCount() > 0) {
                    sb.append(", ").append(o.warehouseStopsCount()).append(" warehouse stop(s)");
                }
                if (o.supplierStopsCount() > 0) {
                    sb.append(", ").append(o.supplierStopsCount()).append(" supplier stop(s)");
                }
                if (o.isChosen()) sb.append("  [CHOSEN]");
                else if (o.isPrimary()) sb.append("  [primary]");
                if (o.viewCount() > 0) sb.append("  (viewed ").append(o.viewCount()).append("x)");
                sb.append("\n");
                if (o.isChosen() || (render == null && o.isPrimary())) render = o;
            }
            if (render == null) render = d.options().get(0);
        }

        if (render != null) {
            sb.append("\nRoute of the ").append(render.objective()).append(" option:\n");
            appendPayloadStops(sb, render.payload(), "route", "house");
            appendPayloadStops(sb, render.payload(), "warehouseStops", "warehouse");
            appendPayloadStops(sb, render.payload(), "supplierStops", "supplier");
            if (render.mapsUrl() != null) {
                sb.append("Google Maps: ").append(render.mapsUrl()).append("\n");
            }
        }

        if (d.events() != null && !d.events().isEmpty()) {
            sb.append("\nEvents:\n");
            for (OrderEventDto e : d.events()) {
                sb.append("  - ").append(e.eventType());
                if (e.objective() != null) sb.append(" (").append(e.objective()).append(")");
                sb.append(" at ").append(e.at());
                if (e.username() != null) sb.append(" by ").append(e.username());
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** Renders one stop list ("route" or "supplierStops") out of the persisted option payload map. */
    private static void appendPayloadStops(StringBuilder sb, Map<String, Object> payload,
                                           String key, String kind) {
        if (payload == null || !(payload.get(key) instanceof List<?> stops) || stops.isEmpty()) return;
        int i = 1;
        for (Object stopObj : stops) {
            if (!(stopObj instanceof Map<?, ?> stop)) continue;
            sb.append("  ").append(i++).append(". [").append(kind).append(" ").append(stop.get("id"))
                .append("] ").append(stop.get("name")).append(" — ").append(stop.get("location")).append("\n");
            if (stop.get("contribution") instanceof Map<?, ?> contrib) {
                for (Object cObj : contrib.values()) {
                    if (!(cObj instanceof Map<?, ?> c)) continue;
                    sb.append("       - ").append(c.get("quantity")).append(" ").append(c.get("unit"))
                        .append(" of ").append(c.get("name")).append("\n");
                }
            }
        }
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String formatQty(double qty) {
        if (qty == Math.floor(qty)) {
            return String.format(Locale.ROOT, "%.0f", qty);
        }
        return String.format(Locale.ROOT, "%.2f", qty);
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
