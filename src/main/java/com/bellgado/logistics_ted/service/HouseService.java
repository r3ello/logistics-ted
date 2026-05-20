package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import com.bellgado.logistics_ted.web.dto.HouseDto;
import com.bellgado.logistics_ted.web.dto.HouseResponse;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import com.bellgado.logistics_ted.web.dto.MaterialLineDto;
import com.bellgado.logistics_ted.web.dto.MaterialTotalDto;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class HouseService {

    private final HouseRepository houses;
    private final WarehouseRepository warehouses;
    private final InventoryRepository inventories;

    public HouseService(HouseRepository houses, WarehouseRepository warehouses, InventoryRepository inventories) {
        this.houses = houses;
        this.warehouses = warehouses;
        this.inventories = inventories;
    }

    @Transactional(readOnly = true)
    public List<HouseDto> listAll() {
        // Assemble houses with their materials. Start from all houses so empty ones still appear,
        // then attach inventory rows.
        Map<Integer, HouseDto.Builder> byId = new LinkedHashMap<>();
        for (House h : houses.findAllByOrderByIdAsc()) {
            byId.put(h.getId(), new HouseDto.Builder(h));
        }
        for (Inventory inv : inventories.findAllWithJoins()) {
            House h = inv.getWarehouse().getHouse();
            Material m = inv.getMaterial();
            BigDecimal subtotal = m.getPrice().multiply(inv.getQuantity());
            HouseDto.Builder b = byId.get(h.getId());
            if (b != null) {
                b.materials.add(new MaterialLineDto(m.getName(), m.getUnit(), m.getPrice(), inv.getQuantity(), subtotal));
                b.totalValue = b.totalValue.add(subtotal);
            }
        }
        List<HouseDto> out = new ArrayList<>(byId.size());
        for (HouseDto.Builder b : byId.values()) out.add(b.build());
        return out;
    }

    @Transactional(readOnly = true)
    public List<MaterialTotalDto> totals() {
        // SUM(quantity), SUM(quantity*price) GROUP BY material — fetch inventories with material and aggregate.
        Map<Integer, MaterialTotalAccumulator> agg = new LinkedHashMap<>();
        for (Inventory inv : inventories.findAllWithJoins()) {
            Material m = inv.getMaterial();
            MaterialTotalAccumulator a = agg.computeIfAbsent(m.getId(), k -> new MaterialTotalAccumulator(m));
            a.total = a.total.add(inv.getQuantity());
            a.totalValue = a.totalValue.add(inv.getQuantity().multiply(m.getPrice()));
        }
        List<MaterialTotalDto> out = new ArrayList<>(agg.size());
        for (MaterialTotalAccumulator a : agg.values()) {
            out.add(new MaterialTotalDto(a.material.getName(), a.material.getUnit(), a.material.getPrice(),
                a.total, a.totalValue));
        }
        return out;
    }

    public HouseResponse create(HouseUpsertRequest req) {
        validateNameLocation(req);
        House h = new House();
        applyFields(h, req);
        h = houses.save(h);
        Warehouse w = new Warehouse();
        w.setHouse(h);
        warehouses.save(w);
        return toResponse(h);
    }

    public HouseResponse update(Integer id, HouseUpsertRequest req) {
        validateNameLocation(req);
        House h = houses.findById(id).orElseThrow(() -> new EntityNotFoundException("House not found"));
        applyFields(h, req);
        return toResponse(houses.save(h));
    }

    public void delete(Integer id) {
        // FK cascades take care of warehouse + inventory rows.
        if (!houses.existsById(id)) throw new EntityNotFoundException("House not found");
        houses.deleteById(id);
    }

    private static void validateNameLocation(HouseUpsertRequest req) {
        if (req == null
            || req.name() == null || req.name().isBlank()
            || req.location() == null || req.location().isBlank()) {
            throw new IllegalArgumentException("Name and location are required.");
        }
    }

    private static void applyFields(House h, HouseUpsertRequest req) {
        h.setName(req.name().trim());
        h.setLocation(req.location().trim());
        h.setLat(req.lat());
        h.setLng(req.lng());
        h.setStartDate(parseDate(req.startDate()));
        h.setCurrentPhase(req.currentPhase() == null || req.currentPhase().isBlank() ? null : req.currentPhase().trim());
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static HouseResponse toResponse(House h) {
        return new HouseResponse(
            h.getId(), h.getName(), h.getLocation(), h.getLat(), h.getLng(),
            h.getStartDate() == null ? null : h.getStartDate().toString(),
            h.getCurrentPhase()
        );
    }

    private static final class MaterialTotalAccumulator {
        final Material material;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        MaterialTotalAccumulator(Material material) { this.material = material; }
    }
}
