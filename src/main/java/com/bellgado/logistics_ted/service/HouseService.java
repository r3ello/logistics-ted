package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.domain.HouseStage;
import com.bellgado.logistics_ted.domain.Inventory;
import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.domain.ScaffoldStatus;
import com.bellgado.logistics_ted.domain.Warehouse;
import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.domain.ImportRef;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import com.bellgado.logistics_ted.repository.ImportRefRepository;
import com.bellgado.logistics_ted.repository.HouseStageRepository;
import com.bellgado.logistics_ted.repository.InventoryRepository;
import com.bellgado.logistics_ted.repository.WarehouseRepository;
import com.bellgado.logistics_ted.storage.DocumentStorageService;
import com.bellgado.logistics_ted.service.importer.impl.HouseImporter;
import com.bellgado.logistics_ted.storage.StorageEvents;
import com.bellgado.logistics_ted.web.dto.HouseDto;
import com.bellgado.logistics_ted.web.dto.HouseResponse;
import com.bellgado.logistics_ted.web.dto.HouseUpsertRequest;
import com.bellgado.logistics_ted.web.dto.MaterialLineDto;
import com.bellgado.logistics_ted.web.dto.MaterialTotalDto;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
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
import java.util.Objects;
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
    private final DocumentStorageService documentStorage;
    private final ApplicationEventPublisher events;
    private final ImportRefRepository importRefs;

    /** Mirrors {@code house.external_id varchar(120)} / {@code import_ref.external_key}. */
    private static final int EXTERNAL_ID_MAX = 120;

    public HouseService(HouseRepository houses, WarehouseRepository warehouses,
                        InventoryRepository inventories, HouseStageRepository houseStages,
                        DocFolderRepository docFolders, HouseTemplateFolderService houseTemplate,
                        DocumentStorageService documentStorage, ApplicationEventPublisher events,
                        ImportRefRepository importRefs) {
        this.houses = houses;
        this.warehouses = warehouses;
        this.inventories = inventories;
        this.houseStages = houseStages;
        this.docFolders = docFolders;
        this.houseTemplate = houseTemplate;
        this.documentStorage = documentStorage;
        this.events = events;
        this.importRefs = importRefs;
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
        String externalId = normalizeExternalId(req.externalId());
        requireExternalIdFree(externalId, null);
        h.setExternalId(externalId);
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
        if (houseFolder != null) {
            houseTemplate.seedTemplate(houseFolder);
            // Mirror the tree into the bucket AFTER this transaction commits, on a background pool.
            // Doing it inline costs 12 sequential PUTs per house, which a CSV import multiplies by
            // the row count while holding the transaction open. The listener also cannot run any
            // earlier than the commit: the template subfolders above are not visible to another
            // connection until then.
            events.publishEvent(new StorageEvents.FolderMirrorRequested(houseFolder.getId()));
        }
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
        // null = untouched (partial updates keep the id); "" clears it.
        if (req.externalId() != null) {
            String previous = h.getExternalId();
            String next = normalizeExternalId(req.externalId());
            if (!Objects.equals(previous, next)) {
                requireExternalIdFree(next, h.getId());
                h.setExternalId(next);
                rekeyImportRefs(h.getId(), next);
            }
        }
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
        // Compute the bucket prefix while the rows still exist, but defer the deletion itself until
        // after this transaction commits: erasing objects inline would leave the files gone for good
        // if anything below rolled the transaction back.
        docFolders.findByFolderType("ACTIVE_SITES")
            .flatMap(dept -> docFolders.findByCodeAndParentId("house_" + id, dept.getId()))
            .ifPresent(f -> events.publishEvent(
                new StorageEvents.PrefixDeletionRequested(documentStorage.prefixFor(f))));
        // Remove matching doc_folder — subfolders + documents cascade automatically via DB
        docFolders.deleteByCode("house_" + id);
        houses.deleteById(id);
    }

    private static void validateNameLocation(HouseUpsertRequest req) {
        // `address` is the required one, not `location`: location is now the optional Maps link.
        if (req == null
            || req.name() == null || req.name().isBlank()
            || req.address() == null || req.address().isBlank()) {
            throw new IllegalArgumentException("Name and address are required.");
        }
    }

    private static String normalizeExternalId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (v.length() > EXTERNAL_ID_MAX) {
            throw new IllegalArgumentException("External ID must be at most " + EXTERNAL_ID_MAX + " characters.");
        }
        return v;
    }

    /** Readable 400 instead of the unique index's 500. The index stays the real guarantee. */
    private void requireExternalIdFree(String externalId, Integer selfId) {
        if (externalId == null) return;
        houses.findByExternalId(externalId)
            .filter(other -> !other.getId().equals(selfId))
            .ifPresent(other -> {
                throw new IllegalArgumentException("External ID '" + externalId
                    + "' is already used by house #" + other.getId() + " (" + other.getName() + ").");
            });
    }

    /**
     * Keeps the CSV sync pointing at this house after its external id is edited, so the next import
     * with the new key updates it instead of creating a duplicate.
     *
     * <p>Invariant after this call: the house has at most one mapping, keyed by its external id.
     * Clearing the id unlinks the house from the sync (its mapping and open conflicts go); a later
     * row with the old key then creates a new house — the user has said this house is not that key.
     */
    private void rekeyImportRefs(Integer houseId, String newKey) {
        String type = HouseImporter.ENTITY_TYPE;
        List<ImportRef> own = importRefs.findByEntityTypeAndEntityId(type, houseId.longValue());
        if (newKey == null) {
            importRefs.deleteAll(own);
            return;
        }
        // A mapping for the new key that points elsewhere can only be dangling (its house was
        // deleted — a live one would carry the id and have failed requireExternalIdFree). Drop it
        // and flush, because Hibernate runs deletes after updates and the re-key below would
        // otherwise hit uq_import_ref.
        importRefs.findByEntityTypeAndExternalKey(type, newKey)
            .filter(r -> !r.getEntityId().equals(houseId.longValue()))
            .ifPresent(r -> {
                if (houses.existsById(r.getEntityId().intValue())) {
                    throw new IllegalArgumentException("External ID '" + newKey
                        + "' is mapped by the import to house #" + r.getEntityId() + ".");
                }
                importRefs.delete(r);
                importRefs.flush();
            });
        if (own.isEmpty()) return;
        ImportRef keep = own.get(0);
        if (own.size() > 1) {
            importRefs.deleteAll(own.subList(1, own.size()));
            importRefs.flush();
        }
        // Baselines stay: they describe the house's values, which have not changed.
        keep.setExternalKey(newKey);
        importRefs.save(keep);
    }

    private static void applyFields(House h, HouseUpsertRequest req) {
        if (req.name()     != null) h.setName(req.name().trim());
        if (req.address()  != null) h.setAddress(req.address().trim());
        if (req.location() != null) h.setLocation(req.location().isBlank() ? null : req.location().trim());
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
        if (req.googleDocUrl() != null) h.setGoogleDocUrl(req.googleDocUrl().isBlank() ? null : req.googleDocUrl().trim());
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
            h.getId(), h.getName(), h.getAddress(), h.getLocation(), h.getLat(), h.getLng(),
            h.getStartDate() == null ? null : h.getStartDate().toString(),
            h.getCurrentPhase()
        );
    }

    private DocFolder createHouseDocFolder(House h) {
        var dept = docFolders.findByFolderType("ACTIVE_SITES");
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

    /** Public because {@code HouseImporter} applies renames directly and must keep the folder in step. */
    public void syncHouseDocFolderName(House h) {
        docFolders.findByFolderType("ACTIVE_SITES").ifPresent(dept ->
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
