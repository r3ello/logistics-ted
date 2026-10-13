package com.bellgado.logistics_ted.service.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.junit.jupiter.api.Test;

/**
 * Pins what {@link ThreeWayMerger}'s per-column "never seen" rule relies on: the import_ref
 * snapshots ({@code @JdbcTypeCode(SqlTypes.JSON)} maps) keep a key whose value is null. If the JSON
 * mapper ever dropped nulls, an empty column would read as unseen and the sheet would overwrite a
 * value set in the app since.
 */
class SnapshotJsonNullsTest {

    @Test
    void nullValuedKeysSurviveTheRoundTrip() {
        JacksonJsonFormatMapper mapper = new JacksonJsonFormatMapper();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("stage_2_crew", null);
        snapshot.put("stage_2_status", "DONE");

        String json = mapper.toString(snapshot, Map.class);
        Map<String, Object> back = mapper.fromString(json, Map.class);

        assertThat(back).containsKey("stage_2_crew");
        assertThat(back.get("stage_2_crew")).isNull();
    }
}
