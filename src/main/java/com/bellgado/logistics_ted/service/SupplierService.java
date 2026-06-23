package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Supplier;
import com.bellgado.logistics_ted.domain.SupplierInventory;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.SupplierInventoryRepository;
import com.bellgado.logistics_ted.repository.SupplierRepository;
import com.bellgado.logistics_ted.web.dto.MaterialLineDto;
import com.bellgado.logistics_ted.web.dto.SupplierDto;
import com.bellgado.logistics_ted.web.dto.SupplierUpsertRequest;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin management for tier-3 external suppliers and their stock. Mirrors {@link DepotService}
 * but each stock line carries the supplier's own unit price (falling back to the catalog
 * material price when not set). Unit price is informational only — the supplier fallback
 * picks by distance, not cost.
 */
@Service
@Transactional
public class SupplierService {

    private final SupplierRepository suppliers;
    private final SupplierInventoryRepository inventories;
    private final MaterialRepository materials;

    public SupplierService(SupplierRepository suppliers, SupplierInventoryRepository inventories,
                           MaterialRepository materials) {
        this.suppliers = suppliers;
        this.inventories = inventories;
        this.materials = materials;
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> listAll() {
        Map<Integer, SupplierDto.Builder> byId = new LinkedHashMap<>();
        for (Supplier s : suppliers.findAllByOrderByIdAsc()) {
            byId.put(s.getId(), new SupplierDto.Builder(s));
        }
        for (SupplierInventory inv : inventories.findAllWithJoins()) {
            Material m = inv.getMaterial();
            BigDecimal price = inv.getUnitPrice() != null ? inv.getUnitPrice() : m.getPrice();
            BigDecimal subtotal = price.multiply(inv.getQuantity());
            SupplierDto.Builder b = byId.get(inv.getSupplier().getId());
            if (b != null) {
                b.materials.add(new MaterialLineDto(m.getName(), m.getUnit(), price,
                    inv.getQuantity(), subtotal));
                b.totalValue = b.totalValue.add(subtotal);
            }
        }
        List<SupplierDto> out = new ArrayList<>(byId.size());
        for (SupplierDto.Builder b : byId.values()) out.add(b.build());
        return out;
    }

    public Supplier create(SupplierUpsertRequest req) {
        validate(req);
        Supplier s = new Supplier();
        apply(s, req);
        return suppliers.save(s);
    }

    public Supplier update(Integer id, SupplierUpsertRequest req) {
        validate(req);
        Supplier s = suppliers.findById(id).orElseThrow(() -> new EntityNotFoundException("Supplier not found"));
        apply(s, req);
        return suppliers.save(s);
    }

    public void delete(Integer id) {
        if (!suppliers.existsById(id)) throw new EntityNotFoundException("Supplier not found");
        suppliers.deleteById(id); // supplier_inventory cascades via FK
    }

    /**
     * Each entry maps a material id to either a plain quantity (number/string) or an object
     * {@code {"quantity": q, "unitPrice": p}}. Negative quantities are skipped.
     */
    public void upsertInventory(Integer supplierId, Map<String, Object> body) {
        Supplier supplier = suppliers.findById(supplierId)
            .orElseThrow(() -> new EntityNotFoundException("Supplier not found"));

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            Integer materialId;
            try {
                materialId = Integer.parseInt(entry.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }

            BigDecimal qty;
            BigDecimal unitPrice = null;
            Object value = entry.getValue();
            try {
                if (value instanceof Map<?, ?> obj) {
                    qty = new BigDecimal(String.valueOf(obj.get("quantity")));
                    Object p = obj.get("unitPrice");
                    if (p != null && !String.valueOf(p).isBlank()) {
                        unitPrice = new BigDecimal(String.valueOf(p));
                    }
                } else {
                    qty = new BigDecimal(String.valueOf(value));
                }
            } catch (NumberFormatException ex) {
                continue;
            }
            if (qty.signum() < 0) continue;

            Material material = materials.findById(materialId).orElse(null);
            if (material == null) continue;

            BigDecimal finalUnitPrice = unitPrice;
            SupplierInventory inv = inventories.findBySupplierIdAndMaterialId(supplier.getId(), materialId)
                .orElseGet(() -> {
                    SupplierInventory n = new SupplierInventory();
                    n.setSupplier(supplier);
                    n.setMaterial(material);
                    return n;
                });
            inv.setQuantity(qty);
            if (finalUnitPrice != null) inv.setUnitPrice(finalUnitPrice);
            inventories.save(inv);
        }
    }

    private static void validate(SupplierUpsertRequest req) {
        if (req == null
            || req.name() == null || req.name().isBlank()
            || req.location() == null || req.location().isBlank()) {
            throw new IllegalArgumentException("Name and location are required.");
        }
    }

    private static void apply(Supplier s, SupplierUpsertRequest req) {
        s.setName(req.name().trim());
        s.setLocation(req.location().trim());
        s.setLat(req.lat());
        s.setLng(req.lng());
    }
}
