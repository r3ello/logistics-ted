package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InventoryService {

    private final WarehouseRepository warehouses;
    private final InventoryRepository inventories;
    private final MaterialRepository materials;

    public InventoryService(WarehouseRepository warehouses, InventoryRepository inventories, MaterialRepository materials) {
        this.warehouses = warehouses;
        this.inventories = inventories;
        this.materials = materials;
    }

    public void upsertForHouse(Integer houseId, Map<String, Object> quantities) {
        Warehouse warehouse = warehouses.findByHouseId(houseId)
            .orElseThrow(() -> new EntityNotFoundException("House not found"));

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

            Inventory inv = inventories.findByWarehouseIdAndMaterialId(warehouse.getId(), materialId)
                .orElseGet(() -> {
                    Inventory n = new Inventory();
                    n.setWarehouse(warehouse);
                    n.setMaterial(material);
                    return n;
                });
            inv.setQuantity(qty);
            inventories.save(inv);
        }
    }
}
