package com.bellgado.logistics_ted.web.importer.csv;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A parsed file: its header (lower-cased, in declaration order) and its data rows.
 *
 * @param headers column names as declared, lower-cased and trimmed
 * @param rows    data rows, blank lines already dropped
 */
public record CsvTable(List<String> headers, List<CsvRow> rows) {

    /** Fails fast when the file cannot possibly be imported, before any row work happens. */
    public void requireColumns(String... required) {
        Set<String> missing = new TreeSet<>();
        for (String c : required) {
            if (!headers.contains(c.trim().toLowerCase())) missing.add(c);
        }
        if (!missing.isEmpty()) {
            throw new CsvException(ImportErrorCode.MISSING_COLUMN,
                "Missing required column(s): " + String.join(", ", missing)
                    + ". Found: " + String.join(", ", headers) + ".");
        }
    }

    /** Columns the file declares that the importer does not know — reported as warnings, not errors. */
    public List<String> unknownColumns(Set<String> known) {
        return headers.stream().filter(h -> !known.contains(h)).toList();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
