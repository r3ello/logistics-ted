package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.DocDocument;
import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.domain.DocFolderTemplate;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.repository.DocDocumentRepository;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.DocFolderTemplateRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseTemplateFolderService {

    private final DocFolderRepository folders;
    private final DocDocumentRepository documents;
    private final HouseRepository houses;
    private final DocFolderTemplateRepository templates;

    public HouseTemplateFolderService(DocFolderRepository folders,
                                      DocDocumentRepository documents,
                                      HouseRepository houses,
                                      DocFolderTemplateRepository templates) {
        this.folders   = folders;
        this.documents = documents;
        this.houses    = houses;
        this.templates = templates;
    }

    @Transactional
    public void seedTemplate(DocFolder houseFolder) {
        List<DocFolderTemplate> all = templates.findAllOrdered();

        // top-level template entries (no parent_code)
        List<DocFolderTemplate> topLevel = all.stream()
            .filter(t -> t.getParentId() == null)
            .toList();

        // children grouped by parent_id
        Map<Integer, List<DocFolderTemplate>> children = all.stream()
            .filter(t -> t.getParentId() != null)
            .collect(Collectors.groupingBy(DocFolderTemplate::getParentId));

        for (DocFolderTemplate t : topLevel) {
            DocFolder sub = getOrCreate(t.getLabelEn(), t.getLabelBg(), houseFolder);
            List<DocFolderTemplate> subs = children.get(t.getId());
            if (subs != null) {
                for (DocFolderTemplate c : subs) {
                    getOrCreate(c.getLabelEn(), c.getLabelBg(), sub);
                }
            }
        }

        // Seed 2 documents on the house folder itself
        seedDoc(houseFolder, "MASTER",     "MASTER",      "SPREADSHEET", 1);
        seedDoc(houseFolder, "Calculator", "Калкулатор",  "SPREADSHEET", 2);
    }

    @Transactional
    public int seedAllHouses() {
        Optional<DocFolder> activeSites = folders.findByFolderType("ACTIVE_SITES");
        if (activeSites.isEmpty()) return 0;
        DocFolder active = activeSites.get();
        List<House> allHouses = houses.findAllByOrderByIdAsc();
        // use the first template's labelEn as the "already seeded" marker
        String firstTemplateLabelEn = templates.findAllOrdered().stream()
            .filter(t -> t.getParentId() == null)
            .findFirst().map(DocFolderTemplate::getLabelEn).orElse(null);
        int count = 0;
        for (House h : allHouses) {
            Optional<DocFolder> hf = folders.findByCodeAndParentId("house_" + h.getId(), active.getId());
            if (hf.isPresent()) {
                boolean alreadySeeded = firstTemplateLabelEn == null ||
                    folders.findByParentId(hf.get().getId()).stream()
                        .anyMatch(f -> firstTemplateLabelEn.equals(f.getLabelEn()));
                if (!alreadySeeded) {
                    seedTemplate(hf.get());
                    count++;
                }
            }
        }
        return count;
    }

    @Transactional
    public void moveToCompleted(Integer houseId) {
        Optional<DocFolder> activeSites    = folders.findByFolderType("ACTIVE_SITES");
        Optional<DocFolder> completedSites = folders.findByFolderType("COMPLETED_SITES");
        if (activeSites.isEmpty() || completedSites.isEmpty()) return;
        folders.findByCodeAndParentId("house_" + houseId, activeSites.get().getId()).ifPresent(f -> {
            f.setParent(completedSites.get());
            folders.save(f);
        });
    }

    @Transactional
    public void moveToActive(Integer houseId) {
        Optional<DocFolder> activeSites    = folders.findByFolderType("ACTIVE_SITES");
        Optional<DocFolder> completedSites = folders.findByFolderType("COMPLETED_SITES");
        if (activeSites.isEmpty() || completedSites.isEmpty()) return;
        folders.findByCodeAndParentId("house_" + houseId, completedSites.get().getId()).ifPresent(f -> {
            f.setParent(activeSites.get());
            folders.save(f);
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DocFolder getOrCreate(String labelEn, String labelBg, DocFolder parent) {
        List<DocFolder> existing = folders.findByParentId(parent.getId());
        return existing.stream()
            .filter(f -> labelEn.equals(f.getLabelEn()))
            .findFirst()
            .orElseGet(() -> {
                DocFolder f = new DocFolder();
                f.setCode(labelEn.toLowerCase().replaceAll("[^a-z0-9]+", "-"));
                f.setLabelEn(labelEn);
                f.setLabelBg(labelBg);
                f.setIcon("");
                f.setColor("#4f8ef7");
                f.setSortOrder(0);
                f.setParent(parent);
                return folders.save(f);
            });
    }

    private void seedDoc(DocFolder folder, String titleEn, String titleBg, String docType, int sortOrder) {
        boolean exists = documents.findByFolderIdOrderBySortOrder(folder.getId()).stream()
            .anyMatch(d -> titleEn.equals(d.getTitleEn()));
        if (!exists) {
            DocDocument d = new DocDocument();
            d.setFolder(folder);
            d.setTitleEn(titleEn);
            d.setTitleBg(titleBg);
            d.setDocType(docType);
            d.setSortOrder(sortOrder);
            documents.save(d);
        }
    }
}
