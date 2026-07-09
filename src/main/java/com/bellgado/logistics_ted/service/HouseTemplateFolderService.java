package com.bellgado.logistics_ted.service;

import com.bellgado.logistics_ted.domain.DocDocument;
import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.domain.House;
import com.bellgado.logistics_ted.repository.DocDocumentRepository;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.HouseRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HouseTemplateFolderService {

    private final DocFolderRepository folders;
    private final DocDocumentRepository documents;
    private final HouseRepository houses;

    public HouseTemplateFolderService(DocFolderRepository folders,
                                      DocDocumentRepository documents,
                                      HouseRepository houses) {
        this.folders   = folders;
        this.documents = documents;
        this.houses    = houses;
    }

    // Template: code → [labelEn, labelBg]
    private static final String[][] TEMPLATE = {
        {"01", "01_ЧЕРТЕЖИ",                          "Drawings"},
        {"02", "02_ДОГОВОРИ_И_ПРИЛОЖЕНИЯ",             "Contracts & Annexes"},
        {"03", "03_СНИМКИ",                            "Photos"},
        {"04", "04_Материали_и_Логистика",             "Materials & Logistics"},
        {"05", "05_Комуникация_с_Клиента",             "Client Communication"},
        {"06", "06_Работна_документация_и_Протоколи",  "Working Docs & Protocols"},
        {"07", "07_ОТЧЕТИ_ТРУД",                       "Labour Reports"},
        {"08", "08_РЕЗУЛТАТИ",                         "Results"},
        {"09", "09_КОНТРОЛ_КАЧЕСТВО",                  "Quality Control"},
    };

    // Sub-folders under code "04"
    private static final String[][] TEMPLATE_04_SUBS = {
        {"04-01", "Заявки",            "Requests"},
        {"04-02", "Остатъчен материал","Residual material"},
    };

    @Transactional
    public void seedTemplate(DocFolder houseFolder) {
        DocFolder folder04 = null;
        for (String[] t : TEMPLATE) {
            String code = t[0], labelBg = t[1], labelEn = t[2];
            DocFolder sub = getOrCreate(code, labelEn, labelBg, houseFolder);
            if ("04".equals(code)) folder04 = sub;
        }
        if (folder04 != null) {
            for (String[] t : TEMPLATE_04_SUBS) {
                getOrCreate(t[0], t[2], t[1], folder04);
            }
        }
        // Seed 2 documents on the house folder itself
        seedDoc(houseFolder, "MASTER", "MASTER", "SPREADSHEET", 1);
        seedDoc(houseFolder, "Calculator", "Калкулатор", "SPREADSHEET", 2);
    }

    @Transactional
    public int seedAllHouses() {
        Optional<DocFolder> activeSites = folders.findTopLevelByCode("02");
        if (activeSites.isEmpty()) return 0;
        DocFolder active = activeSites.get();
        List<House> allHouses = houses.findAllByOrderByIdAsc();
        int count = 0;
        for (House h : allHouses) {
            Optional<DocFolder> hf = folders.findByCodeAndParentId("house_" + h.getId(), active.getId());
            if (hf.isPresent()) {
                // Check if template already seeded (01 subfolder exists)
                boolean alreadySeeded = folders.findByParentId(hf.get().getId()).stream()
                    .anyMatch(f -> "01".equals(f.getCode()));
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
        Optional<DocFolder> activeSites    = folders.findTopLevelByCode("02");
        Optional<DocFolder> completedSites = folders.findTopLevelByCode("07");
        if (activeSites.isEmpty() || completedSites.isEmpty()) return;
        folders.findByCodeAndParentId("house_" + houseId, activeSites.get().getId()).ifPresent(f -> {
            f.setParent(completedSites.get());
            folders.save(f);
        });
    }

    @Transactional
    public void moveToActive(Integer houseId) {
        Optional<DocFolder> activeSites    = folders.findTopLevelByCode("02");
        Optional<DocFolder> completedSites = folders.findTopLevelByCode("07");
        if (activeSites.isEmpty() || completedSites.isEmpty()) return;
        folders.findByCodeAndParentId("house_" + houseId, completedSites.get().getId()).ifPresent(f -> {
            f.setParent(activeSites.get());
            folders.save(f);
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private DocFolder getOrCreate(String code, String labelEn, String labelBg, DocFolder parent) {
        List<DocFolder> existing = folders.findByParentId(parent.getId());
        return existing.stream()
            .filter(f -> code.equals(f.getCode()))
            .findFirst()
            .orElseGet(() -> {
                DocFolder f = new DocFolder();
                f.setCode(code);
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
