package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Depot;
import com.bellgado.logistics_ted.domain.DepotInventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.DepotInventoryRepository;
import com.bellgado.logistics_ted.repository.DepotRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.web.dto.DepotDto;
import com.bellgado.logistics_ted.web.dto.DepotUpsertRequest;
import com.bellgado.logistics_ted.web.dto.MaterialLineDto;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management for tier-2 warehouses (depots) and their stock. Mirrors the
 * house/inventory services so the dashboard can offer the same create/edit/delete + stock
 * editing it has for houses.
 */
@Service
@Transactional
public class DepotService {

    private final DepotRepository depots;
    private final DepotInventoryRepository inventories;
    private final MaterialRepository materials;

    public DepotService(DepotRepository depots, DepotInventoryRepository inventories,
                        MaterialRepository materials) {
        this.depots = depots;
        this.inventories = inventories;
        this.materials = materials;
    }

    @Transactional(readOnly = true)
    public List<DepotDto> listAll() {
        Map<Integer, DepotDto.Builder> byId = new LinkedHashMap<>();
        for (Depot d : depots.findAllByOrderByIdAsc()) {
            byId.put(d.getId(), new DepotDto.Builder(d));
        }
        for (DepotInventory inv : inventories.findAllWithJoins()) {
            Material m = inv.getMaterial();
            BigDecimal subtotal = m.getPrice().multiply(inv.getQuantity());
            DepotDto.Builder b = byId.get(inv.getDepot().getId());
            if (b != null) {
                b.materials.add(new MaterialLineDto(m.getName(), m.getUnit(), m.getPrice(),
                    inv.getQuantity(), subtotal));
                b.totalValue = b.totalValue.add(subtotal);
            }
        }
        List<DepotDto> out = new ArrayList<>(byId.size());
        for (DepotDto.Builder b : byId.values()) out.add(b.build());
        return out;
    }

    public Depot create(DepotUpsertRequest req) {
        validate(req);
        Depot d = new Depot();
        apply(d, req);
        return depots.save(d);
    }

    public Depot update(Integer id, DepotUpsertRequest req) {
        validate(req);
        Depot d = depots.findById(id).orElseThrow(() -> new EntityNotFoundException("Warehouse not found"));
        apply(d, req);
        return depots.save(d);
    }

    public void delete(Integer id) {
        if (!depots.existsById(id)) throw new EntityNotFoundException("Warehouse not found");
        depots.deleteById(id); // depot_inventory cascades via FK
    }

    public void upsertInventory(Integer depotId, Map<String, Object> quantities) {
        Depot depot = depots.findById(depotId)
            .orElseThrow(() -> new EntityNotFoundException("Warehouse not found"));

        for (Map.Entry<String, Object> entry : quantities.entrySet()) {
            Integer materialId;
            BigDecimal qty;
            try {
                materialId = Integer.parseInt(entry.getKey());
                qty = new BigDecimal(String.valueOf(entry.getValue()));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (qty.signum() < 0) continue;

            Material material = materials.findById(materialId).orElse(null);
            if (material == null) continue;

            DepotInventory inv = inventories.findByDepotIdAndMaterialId(depot.getId(), materialId)
                .orElseGet(() -> {
                    DepotInventory n = new DepotInventory();
                    n.setDepot(depot);
                    n.setMaterial(material);
                    return n;
                });
            inv.setQuantity(qty);
            inventories.save(inv);
        }
    }

    private static void validate(DepotUpsertRequest req) {
        if (req == null
            || req.name() == null || req.name().isBlank()
            || req.location() == null || req.location().isBlank()) {
            throw new IllegalArgumentException("Name and location are required.");
        }
    }

    private static void apply(Depot d, DepotUpsertRequest req) {
        d.setName(req.name().trim());
        d.setLocation(req.location().trim());
        d.setLat(req.lat());
        d.setLng(req.lng());
    }
}
