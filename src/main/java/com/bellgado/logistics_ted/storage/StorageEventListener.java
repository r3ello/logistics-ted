package com.bellgado.logistics_ted.storage;

import com.bellgado.logistics_ted.repository.DocFolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs bucket work after the caller's transaction has committed, on {@link StorageAsyncConfig}'s
 * pool.
 *
 * <p><b>Why AFTER_COMMIT and not merely another thread.</b> A plain background thread started from
 * inside {@code HouseService.create} would use a different connection and therefore could not see
 * the caller's uncommitted rows. During a CSV import — one transaction for the whole file — it would
 * find no {@code doc_folder} children at all and mirror an empty house folder, without any error.
 *
 * <p>The same phase fixes a durability problem on the other side: bucket deletions used to happen
 * inside the deleting transaction, so a rollback afterwards left the rows alive with their files
 * already erased. Deferring past the commit means a rollback simply leaves the objects in place.
 *
 * <p>Every failure is swallowed and logged. Both operations are best-effort by contract, and by the
 * time they run the user's request has already succeeded — there is nobody left to fail.
 */
@Component
public class StorageEventListener {

    private static final Logger log = LoggerFactory.getLogger(StorageEventListener.class);

    private final DocumentStorageService documents;
    private final DocFolderRepository folders;

    public StorageEventListener(DocumentStorageService documents, DocFolderRepository folders) {
        this.documents = documents;
        this.folders = folders;
    }

    /** REQUIRES_NEW because the originating transaction is gone; the folder is re-read here. */
    @Async(StorageAsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onFolderMirrorRequested(StorageEvents.FolderMirrorRequested event) {
        try {
            folders.findById(event.folderId()).ifPresent(documents::mirrorTree);
        } catch (RuntimeException e) {
            log.warn("Background folder mirroring failed for folder {}: {}",
                event.folderId(), e.getMessage());
        }
    }

    @Async(StorageAsyncConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPrefixDeletionRequested(StorageEvents.PrefixDeletionRequested event) {
        // deleteTreeByPrefix already swallows storage failures; this guards the rest (an @Async void
        // method that throws only reaches Spring's uncaught-exception handler, which helps nobody).
        try {
            documents.deleteTreeByPrefix(event.prefix());
        } catch (RuntimeException e) {
            log.warn("Background prefix deletion failed for '{}': {}", event.prefix(), e.getMessage());
        }
    }
}
