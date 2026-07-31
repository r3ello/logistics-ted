package com.bellgado.logistics_ted.service.importer;

import com.bellgado.logistics_ted.domain.ImportBatch;
import com.bellgado.logistics_ted.repository.AppUserRepository;
import com.bellgado.logistics_ted.repository.ImportBatchRepository;
import com.bellgado.logistics_ted.web.importer.ImportReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the run record for an import.
 *
 * <p>Both methods are {@code REQUIRES_NEW}, for the same reason {@code OrderHistoryService} and
 * {@code AuditLogService} are: the run record must survive the outcome of the run. An
 * {@code onError=abort} import deliberately rolls its own transaction back, and the batch row
 * explaining *why* is exactly what must not disappear with it.
 *
 * <p>The batch id is also needed before the first {@code import_ref} is written
 * ({@code last_batch_id} references it), so {@link #start} commits up front rather than at the end.
 */
@Component
public class ImportBatchRecorder {

    private final ImportBatchRepository batches;
    private final AppUserRepository users;
    private final ObjectMapper json;

    public ImportBatchRecorder(ImportBatchRepository batches, AppUserRepository users, ObjectMapper json) {
        this.batches = batches;
        this.users = users;
        this.json = json;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportBatch start(String entityType, String mode, String filename, String username) {
        ImportBatch b = new ImportBatch();
        b.setPublicId(UUID.randomUUID());
        b.setStartedAt(Instant.now());
        b.setEntityType(entityType);
        b.setMode(mode);
        b.setFilename(filename);
        b.setUsername(username);
        b.setStatus("running");
        if (username != null) {
            users.findByUsername(username).ifPresent(u -> b.setAppUserId(u.getId()));
        }
        return batches.save(b);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long batchId, ImportReport report) {
        batches.findById(batchId).ifPresent(b -> {
            b.setFinishedAt(Instant.now());
            b.setStatus(report.status());
            b.setRowsRead(report.rowsRead());
            b.setRowsCreated(report.created());
            b.setRowsUpdated(report.updated());
            b.setRowsUnchanged(report.unchanged());
            b.setRowsKeptApp(report.keptApp());
            b.setRowsConflict(report.conflicts());
            b.setRowsFailed(report.failed());
            b.setReportJson(json.convertValue(report, Map.class));
            batches.save(b);
        });
    }
}
