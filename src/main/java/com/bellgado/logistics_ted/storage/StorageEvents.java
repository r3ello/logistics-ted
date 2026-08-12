package com.bellgado.logistics_ted.storage;

/**
 * Bucket work deferred out of the caller's transaction.
 *
 * <p>Both carry plain values, never entities: the listener runs on another thread after the
 * transaction has committed, where a JPA entity from the original persistence context would be
 * detached and its lazy associations dead.
 */
public final class StorageEvents {

    private StorageEvents() {}

    /**
     * Create the empty marker objects mirroring a folder subtree.
     *
     * <p>Carries the id, not the folder: the listener re-reads it in its own transaction. Reading it
     * any earlier is the trap this whole mechanism exists to avoid — during a CSV import the
     * template subfolders are not committed yet, so a listener that ran yesterday's way would mirror
     * the house folder and silently miss all 11 children.
     */
    public record FolderMirrorRequested(Integer folderId) {}

    /**
     * Delete every object under a prefix.
     *
     * <p>Carries the prefix already computed, because by the time this runs the {@code doc_folder}
     * rows it was derived from are gone.
     */
    public record PrefixDeletionRequested(String prefix) {}
}
