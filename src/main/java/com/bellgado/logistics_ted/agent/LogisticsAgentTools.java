package com.bellgado.logistics_ted.agent;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.config.RoutingProperties;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import com.bellgado.logistics_ted.service.RouteOptimizationService;
import com.bellgado.logistics_ted.web.dto.OrderRequest;
import com.bellgado.logistics_ted.web.dto.OrderResponse;
import com.bellgado.logistics_ted.web.dto.OrderResponse.DeficitDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteOptionDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.RouteStopDto;
import com.bellgado.logistics_ted.web.dto.OrderResponse.StopContributionDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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
    private final RouteOptimizationService routeOptimization;
    private final RoutingProperties routingProperties;
    private final ObjectMapper objectMapper;

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
            OrderResponse resp = routeOptimization.calculate(req);

            return formatOrderResponse(resp);
        } catch (RouteOptimizationService.OrderValidationException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            log.warn("calculateOrder failed: {}", e.getMessage(), e);
            return "Error: " + safeMessage(e);
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    /**
     * Compact, LLM-friendly text rendering of the order response. We avoid dumping the full JSON because
     * the route DTO is verbose and the LLM tends to over-quote it back to the user. This summary
     * preserves the essentials: origin, destination, totals, every stop with material contributions,
     * supplier fallback stops, any deficits, the Google Maps link, and the per-objective alternatives.
     */
    private String formatOrderResponse(OrderResponse resp) {
        StringBuilder sb = new StringBuilder();
        sb.append("ROUTE RESULT\n");
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

        if (resp.supplierStops() != null && !resp.supplierStops().isEmpty()) {
            sb.append("\nSupplier stops (fallback for what houses couldn't cover):\n");
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
