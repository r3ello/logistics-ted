package com.bellgado.logistics_ted.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's own behaviour. The {@code AFTER_COMMIT} phase and the {@code @Async} hand-off are
 * Spring's to honour and need a context to exercise — what is checked here is that the listener
 * re-reads the folder by id (rather than trusting a detached entity) and that nothing it does can
 * escape as an exception, since by then the user's request has already returned.
 */
class StorageEventListenerTest {

    private DocumentStorageService documents;
    private DocFolderRepository folders;
    private StorageEventListener listener;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentStorageService.class);
        folders   = mock(DocFolderRepository.class);
        listener  = new StorageEventListener(documents, folders);
    }

    @Test
    void mirroringReloadsTheFolderByIdAndDelegates() {
        DocFolder f = new DocFolder();
        f.setId(40);
        f.setCode("house_12");
        when(folders.findById(40)).thenReturn(Optional.of(f));

        listener.onFolderMirrorRequested(new StorageEvents.FolderMirrorRequested(40));

        verify(folders).findById(40);
        verify(documents).mirrorTree(f);
    }

    @Test
    void aFolderDeletedBetweenCommitAndDispatchIsANoOp() {
        // The house could have been removed in the seconds between the commit and this task running.
        when(folders.findById(40)).thenReturn(Optional.empty());

        listener.onFolderMirrorRequested(new StorageEvents.FolderMirrorRequested(40));

        verify(documents, never()).mirrorTree(any());
    }

    @Test
    void aFailureNeverEscapesTheListener() {
        // There is no caller left to handle it — the request that triggered this already succeeded.
        when(folders.findById(40)).thenThrow(new IllegalStateException("db gone"));

        listener.onFolderMirrorRequested(new StorageEvents.FolderMirrorRequested(40));   // no throw
    }

    @Test
    void prefixDeletionDelegatesStraightThrough() {
        // deleteTreeByPrefix already swallows its own failures, so the listener just forwards.
        doThrow(new StorageException("unreachable")).when(documents).deleteTreeByPrefix(anyString());

        listener.onPrefixDeletionRequested(new StorageEvents.PrefixDeletionRequested("02/house_12"));

        verify(documents).deleteTreeByPrefix("02/house_12");
    }
}
