package com.bellgado.logistics_ted.service.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bellgado.logistics_ted.domain.ImportRef;
import com.bellgado.logistics_ted.repository.ImportRefRepository;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalKeyResolverTest {

    private final ImportRefRepository repo = mock(ImportRefRepository.class);
    private final ExternalKeyResolver resolver = new ExternalKeyResolver(repo);

    {
        when(repo.save(any(ImportRef.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void aPartialFileKeepsTheSheetBaselineOfTheColumnsItDidNotCarry() {
        ImportRef ref = new ImportRef();
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("name", "Широки дол Теодор");
        full.put("location", "https://maps.app.goo.gl/x");
        full.put("lat", null);
        ref.setSourceSnapshot(full);

        Map<String, String> coordsOnly = new HashMap<>();
        coordsOnly.put("name", "Широки дол Теодор");
        coordsOnly.put("lat", "42.700000");
        resolver.rebase(ref, Map.of("name", "Широки дол Теодор"), coordsOnly, 7L);

        assertThat(ExternalKeyResolver.sheetBaselineOf(ref))
            .containsEntry("location", "https://maps.app.goo.gl/x")   // kept, not dropped
            .containsEntry("lat", "42.700000")                         // overwritten by this file
            .containsEntry("name", "Широки дол Теодор");
    }

    @Test
    void anEmptyCellStillOverwritesTheBaseline() {
        ImportRef ref = new ImportRef();
        ref.setSourceSnapshot(new LinkedHashMap<>(Map.of("location", "https://maps.app.goo.gl/x")));
        Map<String, String> cleared = new HashMap<>();
        cleared.put("location", null);
        resolver.rebase(ref, Map.of(), cleared, 8L);
        assertThat(ExternalKeyResolver.sheetBaselineOf(ref).get("location")).isNull();
    }
}
