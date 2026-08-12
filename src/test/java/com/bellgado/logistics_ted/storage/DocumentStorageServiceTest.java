package com.bellgado.logistics_ted.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.DocFolder;
import com.bellgado.logistics_ted.domain.StoredObject;
import com.bellgado.logistics_ted.repository.DocFolderRepository;
import com.bellgado.logistics_ted.repository.StoredObjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the parts that have no bucket in them: key derivation, the guards, and the ordering
 * invariants that decide what survives a half-failed upload or delete.
 */
class DocumentStorageServiceTest {

    private ObjectStorage storage;
    private DocFolderRepository folders;
    private StoredObjectRepository objects;
    private DocumentStorageService service;

    private static final StorageProperties PROPS = new StorageProperties(
        true, "https://eu2.contabostorage.com", "us-east-1", "tedhouse",
        "key", "secret", true, 15, 60, 1_000_000L);

    @BeforeEach
    void setUp() {
        storage = mock(ObjectStorage.class);
        folders = mock(DocFolderRepository.class);
        objects = mock(StoredObjectRepository.class);
        when(objects.save(any(StoredObject.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new DocumentStorageService(storage, PROPS, folders, objects);
    }

    private static DocFolder folder(Integer id, String code, DocFolder parent) {
        DocFolder f = new DocFolder();
        f.setId(id);
        f.setCode(code);
        f.setLabelEn("label");
        f.setLabelBg("етикет");
        f.setParent(parent);
        return f;
    }

    // ── key derivation ───────────────────────────────────────────────────────

    @Test
    void prefixMirrorsTheFolderPathUsingCodes() {
        DocFolder root  = folder(3, "02", null);
        DocFolder house = folder(40, "house_12", root);
        DocFolder photos = folder(41, "photos", house);

        assertThat(service.prefixFor(photos)).isEqualTo("02/house_12/photos");
    }

    @Test
    void prefixIsLowerCasedAndAsciiSoOneFolderCannotYieldTwoSpellings() {
        DocFolder root = folder(1, "Working Docs & Protocols", null);
        assertThat(service.prefixFor(folder(2, "Sub Folder", root)))
            .isEqualTo("working-docs-protocols/sub-folder");
    }

    @Test
    void prefixWalkStopsOnACycleInsteadOfHanging() {
        // parent_id has no constraint preventing a loop; a hung request would be the worse failure.
        DocFolder a = folder(1, "a", null);
        DocFolder b = folder(2, "b", a);
        a.setParent(b);

        assertThat(service.prefixFor(b).split("/")).hasSizeLessThanOrEqualTo(12);
    }

    @Test
    void filenamesAreSanitisedToAsciiButTheExtensionSurvives() {
        // Cyrillic collapses away entirely and the leading separator is trimmed. Acceptable: the
        // key only has to be unique and typeable, and the real name lives in original_filename.
        assertThat(DocumentStorageService.sanitiseFilename("Договор №5.pdf")).isEqualTo("5.pdf");
        assertThat(DocumentStorageService.sanitiseFilename("site photo (1).JPG")).isEqualTo("site-photo-1.jpg");
        assertThat(DocumentStorageService.sanitiseFilename("plain.pdf")).isEqualTo("plain.pdf");
    }

    @Test
    void aPathSentByTheBrowserIsStrippedSoItCannotEscapeThePrefix() {
        assertThat(DocumentStorageService.sanitiseFilename("../../etc/passwd")).isEqualTo("passwd");
        assertThat(DocumentStorageService.sanitiseFilename("C:\\Users\\ralph\\a.pdf")).isEqualTo("a.pdf");
    }

    @Test
    void anExtensionOnlyOrEmptyNameStillYieldsAUsableKeySegment() {
        assertThat(DocumentStorageService.sanitiseFilename("")).isEqualTo("file");
        assertThat(DocumentStorageService.sanitiseFilename(null)).isEqualTo("file");
        assertThat(DocumentStorageService.sanitiseFilename("файл")).isEqualTo("file");
    }

    // ── direct upload (presigned PUT) ────────────────────────────────────────

    @Test
    void theUploadUrlReservesAServerChosenKeyUnderTheFolderPrefix() {
        // The browser never picks the key: it could otherwise write anywhere in the bucket.
        DocFolder root  = folder(3, "02", null);
        DocFolder house = folder(40, "house_12", root);
        when(folders.findById(40)).thenReturn(Optional.of(house));
        when(storage.presignedPut(anyString(), any(), anyString())).thenReturn("https://bucket/put?sig");

        var ticket = service.createUploadUrl(40, "Договор №5.pdf", "application/pdf", 5_000, "admin");

        assertThat(ticket.uploadUrl()).isEqualTo("https://bucket/put?sig");
        assertThat(ticket.contentType()).isEqualTo("application/pdf");

        var saved = ArgumentCaptor.forClass(StoredObject.class);
        verify(objects).save(saved.capture());
        StoredObject o = saved.getValue();
        assertThat(o.getObjectKey())
            .startsWith("02/house_12/")
            .endsWith("-5.pdf")            // "<uuid>-" + sanitised "5.pdf"
            .contains(o.getPublicId().toString());
        // The key is ASCII, but the name the user sees is untouched.
        assertThat(o.getOriginalFilename()).isEqualTo("Договор №5.pdf");
        assertThat(o.getBucket()).isEqualTo("tedhouse");
        assertThat(o.getUploadedBy()).isEqualTo("admin");
    }

    @Test
    void theRowStaysPendingUntilTheUploadIsConfirmed() {
        // Otherwise a half-finished upload would appear in the listing as a file that 404s.
        DocFolder f = folder(40, "house_12", null);
        when(folders.findById(40)).thenReturn(Optional.of(f));
        when(storage.presignedPut(anyString(), any(), anyString())).thenReturn("https://bucket/put");

        service.createUploadUrl(40, "a.pdf", "application/pdf", 10, "admin");

        var saved = ArgumentCaptor.forClass(StoredObject.class);
        verify(objects).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("pending");
    }

    @Test
    void confirmTakesTheSizeFromTheBucketNotFromTheBrowser() {
        // The browser declared 10 bytes at step 1; only the bucket knows what actually arrived.
        StoredObject o = new StoredObject();
        o.setObjectKey("02/house_12/x-a.pdf");
        o.setSizeBytes(10);
        o.setStatus("pending");
        when(objects.findByPublicId(o.getPublicId())).thenReturn(Optional.of(o));
        when(storage.head("02/house_12/x-a.pdf"))
            .thenReturn(Optional.of(new ObjectStorage.ObjectHead(987_654, "\"etag\"", "video/mp4")));

        StoredObject confirmed = service.confirmUpload(o.getPublicId());

        assertThat(confirmed.getSizeBytes()).isEqualTo(987_654);
        assertThat(confirmed.getEtag()).isEqualTo("\"etag\"");
        assertThat(confirmed.getContentType()).isEqualTo("video/mp4");
        assertThat(confirmed.getStatus()).isEqualTo("ready");
    }

    @Test
    void confirmFailsAndLeavesTheRowPendingWhenNothingLanded() {
        StoredObject o = new StoredObject();
        o.setObjectKey("02/house_12/x-a.pdf");
        o.setStatus("pending");
        when(objects.findByPublicId(o.getPublicId())).thenReturn(Optional.of(o));
        when(storage.head(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmUpload(o.getPublicId()))
            .isInstanceOf(StorageException.class)
            .hasMessageContaining("did not complete");
        assertThat(o.getStatus()).isEqualTo("pending");
    }

    @Test
    void emptyAndOversizedFilesAreRejectedBeforeAnyUrlIsIssued() {
        DocFolder f = folder(40, "house_12", null);
        when(folders.findById(40)).thenReturn(Optional.of(f));

        assertThatThrownBy(() -> service.createUploadUrl(40, "a.pdf", "application/pdf", 0, "admin"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createUploadUrl(40, "a.pdf", "application/pdf", 2_000_000L, "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("larger than");

        verify(storage, never()).presignedPut(anyString(), any(), anyString());
        verify(objects, never()).save(any());
    }

    @Test
    void listingShowsOnlyConfirmedFiles() {
        service.list(40);
        verify(objects).findReadyByFolderId(40);
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowThenTheObject() {
        // Row first: if the bucket call throws, the transaction rolls back and the file stays
        // listed. The reverse order could hide a file that still exists.
        StoredObject o = new StoredObject();
        o.setObjectKey("02/house_12/x-a.pdf");
        UUID id = o.getPublicId();
        when(objects.findByPublicId(id)).thenReturn(Optional.of(o));

        service.delete(id);

        var order = inOrder(objects, storage);
        order.verify(objects).delete(o);
        order.verify(storage).delete("02/house_12/x-a.pdf");
    }

    // ── mirroring ────────────────────────────────────────────────────────────

    @Test
    void mirroringWalksTheSubtree() {
        DocFolder house = folder(40, "house_12", null);
        DocFolder photos = folder(41, "photos", house);
        when(folders.findByParentId(40)).thenReturn(List.of(photos));
        when(folders.findByParentId(41)).thenReturn(List.of());

        service.mirrorTree(house);

        verify(storage).createFolder("house_12");
        verify(storage).createFolder("house_12/photos");
    }

    @Test
    void mirroringNeverPropagatesAStorageFailure() {
        // It runs inside HouseService.create — an unreachable bucket must not stop a house being
        // created. The prefixes appear on first upload anyway, since S3 needs no folder to exist.
        DocFolder house = folder(40, "house_12", null);
        when(folders.findByParentId(40)).thenReturn(List.of());
        doThrow(new StorageException("unreachable")).when(storage).createFolder(anyString());

        service.mirrorTree(house);   // must not throw
        verify(storage).createFolder(eq("house_12"));
    }

    @Test
    void deletingATreeNeverPropagatesEither() {
        doThrow(new StorageException("unreachable")).when(storage).deletePrefix(anyString());

        service.deleteTreeByPrefix("house_12");   // must not throw
        verify(storage).deletePrefix("house_12/");
    }

    @Test
    void deletingATreeTakesAPrefixBecauseItRunsAfterTheRowsAreGone() {
        // It is fed a string, not a folder, precisely because the doc_folder rows it came from no
        // longer exist by the time the after-commit listener calls this.
        service.deleteTreeByPrefix("02/house_12/");
        verify(storage).deletePrefix("02/house_12/");   // already trailing-slashed, not doubled
    }
}
