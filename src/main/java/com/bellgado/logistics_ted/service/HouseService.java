package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import com.bellgado.logistics_ted.web.dto.HouseDto;
import com.bellgado.logistics_ted.web.dto.HouseResponse;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import com.bellgado.logistics_ted.web.dto.MaterialLineDto;
import com.bellgado.logistics_ted.web.dto.MaterialTotalDto;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class HouseService {

    private final HouseRepository houses;
    private final WarehouseRepository warehouses;
    private final InventoryRepository inventories;
    private final HouseStageRepository houseStages;
    private final DocFolderRepository docFolders;
    private final HouseTemplateFolderService houseTemplate;

    public HouseService(HouseRepository houses, WarehouseRepository warehouses,
                        InventoryRepository inventories, HouseStageRepository houseStages,
                        DocFolderRepository docFolders, HouseTemplateFolderService houseTemplate) {
        this.houses = houses;
        this.warehouses = warehouses;
        this.inventories = inventories;
        this.houseStages = houseStages;
        this.docFolders = docFolders;
        this.houseTemplate = houseTemplate;
    }

    @Transactional(readOnly = true)
    public List<HouseDto> listAll() {
        // Assemble houses with their materials. Start from all houses so empty ones still appear,
        // then attach inventory rows.
        Map<Integer, HouseDto.Builder> byId = new LinkedHashMap<>();
        for (House h : houses.findAllByOrderByIdAsc()) {
            byId.put(h.getId(), new HouseDto.Builder(h));
        }
        for (Object[] row : houseStages.findAllHouseCrewMappings()) {
            Integer houseId = ((Number) row[0]).intValue();
            Integer crewId  = ((Number) row[1]).intValue();
            String  crewName = (String) row[2];
            HouseDto.Builder b = byId.get(houseId);
            if (b != null) b.crews.add(new HouseDto.CrewRefDto(crewId, crewName));
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
        // Auto-assign a unique check-in QR token
        h.setCheckinToken(generateCheckinToken(h.getId()));
        houses.save(h);
        Warehouse w = new Warehouse();
        w.setHouse(h);
        warehouses.save(w);
        // Seed house_stage rows for every existing stage type
        final House savedHouse = h;
        List<HouseStage> stageRows = new ArrayList<>();
        for (Object[] st : houseStages.findDistinctStageTypes()) {
            HouseStage hs = new HouseStage();
            hs.setHouse(savedHouse);
            hs.setStageOrder((Integer) st[0]);
            hs.setStageName((String) st[1]);
            hs.setStageNameEn((String) st[2]);
            hs.setStatus("NOT_STARTED");
            hs.setUpdatedAt(LocalDateTime.now());
            stageRows.add(hs);
        }
        houseStages.saveAll(stageRows);
        DocFolder houseFolder = createHouseDocFolder(savedHouse);
        if (houseFolder != null) houseTemplate.seedTemplate(houseFolder);
        return toResponse(h);
    }

    /** Generates a unique 64-char hex token for a house check-in QR code. */
    private String generateCheckinToken(Integer houseId) {
        try {
            String input = houseId + "-checkin-tedhouse-" + UUID.randomUUID();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            // Fallback — should never happen
            return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        }
    }

    public HouseResponse update(Integer id, HouseUpsertRequest req) {
        validateNameLocation(req);
        House h = houses.findById(id).orElseThrow(() -> new EntityNotFoundException("House not found"));
        applyFields(h, req);
        HouseResponse res = toResponse(houses.save(h));
        syncHouseDocFolderName(h);
        return res;
    }

    public void updateScaffold(Integer id, Map<String, Object> body) {
        House h = houses.findById(id).orElseThrow(() -> new IllegalArgumentException("House not found"));
        if (body.containsKey("scaffoldStatus") && body.get("scaffoldStatus") != null)
            h.setScaffoldStatus(ScaffoldStatus.valueOf(body.get("scaffoldStatus").toString()));
        String start = body.containsKey("scaffoldStartDate") ? (String) body.get("scaffoldStartDate") : null;
        String end   = body.containsKey("scaffoldEndDate")   ? (String) body.get("scaffoldEndDate")   : null;
        if (body.containsKey("scaffoldStartDate")) h.setScaffoldStartDate(parseDate(start));
        if (body.containsKey("scaffoldEndDate"))   h.setScaffoldEndDate(parseDate(end));
        houses.save(h);
    }

    public void delete(Integer id) {
        // FK cascades take care of warehouse + inventory rows.
        if (!houses.existsById(id)) throw new EntityNotFoundException("House not found");
        // Remove matching doc_folder — subfolders + documents cascade automatically via DB
        docFolders.deleteByCode("house_" + id);
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
        if (req.name()     != null) h.setName(req.name().trim());
        if (req.location() != null) h.setLocation(req.location().trim());
        if (req.lat()      != null) h.setLat(req.lat());
        if (req.lng()      != null) h.setLng(req.lng());
        if (req.startDate()    != null || req.name() != null) h.setStartDate(parseDate(req.startDate()));
        if (req.currentPhase() != null || req.name() != null)
            h.setCurrentPhase(req.currentPhase() == null || req.currentPhase().isBlank() ? null : req.currentPhase().trim());
        if (req.scaffoldStatus()    != null) h.setScaffoldStatus(req.scaffoldStatus());
        if (req.scaffoldStartDate() != null) h.setScaffoldStartDate(parseDate(req.scaffoldStartDate()));
        if (req.scaffoldEndDate()   != null) h.setScaffoldEndDate(parseDate(req.scaffoldEndDate()));
        // allow clearing dates
        if ("".equals(req.scaffoldStartDate())) h.setScaffoldStartDate(null);
        if ("".equals(req.scaffoldEndDate()))   h.setScaffoldEndDate(null);
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

    private DocFolder createHouseDocFolder(House h) {
        var dept = docFolders.findTopLevelByCode("02");
        if (dept.isEmpty()) return null;
        String code = "house_" + h.getId();
        var existing = docFolders.findByCodeAndParentId(code, dept.get().getId());
        if (existing.isPresent()) return existing.get();
        DocFolder f = new DocFolder();
        f.setCode(code);
        f.setLabelEn(h.getName());
        f.setLabelBg(h.getName());
        f.setIcon("🏠");
        f.setColor("#f97316");
        f.setSortOrder(h.getId());
        f.setParent(dept.get());
        return docFolders.save(f);
    }

    private void syncHouseDocFolderName(House h) {
        docFolders.findTopLevelByCode("02").ifPresent(dept ->
            docFolders.findByCodeAndParentId("house_" + h.getId(), dept.getId()).ifPresent(f -> {
                f.setLabelEn(h.getName());
                f.setLabelBg(h.getName());
                docFolders.save(f);
            })
        );
    }

    private static final class MaterialTotalAccumulator {
        final Material material;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        MaterialTotalAccumulator(Material material) { this.material = material; }
    }
}
