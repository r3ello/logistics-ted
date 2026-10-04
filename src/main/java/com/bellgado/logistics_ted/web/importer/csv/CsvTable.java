package com.bellgado.logistics_ted.web.importer.csv;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

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

    /**
     * The same table with its headers renamed (see {@code EntityImporter#headerResolver}). Two
     * headers that resolve to one name are a {@code DUPLICATE_COLUMN}, exactly like two identical
     * headers in the file — picking one would make the result depend on column order.
     */
    public CsvTable renameHeaders(UnaryOperator<String> resolver) {
        Map<String, String> renamed = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String h : headers) {
            String to = resolver.apply(h);
            to = to == null ? h : to.trim().toLowerCase();
            if (!seen.add(to)) duplicates.add(to);
            renamed.put(h, to);
        }
        if (!duplicates.isEmpty()) {
            throw new CsvException(ImportErrorCode.DUPLICATE_COLUMN,
                "Duplicate column(s) after resolving the header: " + String.join(", ", duplicates) + ".");
        }
        if (renamed.entrySet().stream().allMatch(e -> e.getKey().equals(e.getValue()))) return this;
        List<CsvRow> out = new ArrayList<>(rows.size());
        for (CsvRow r : rows) out.add(r.renamed(renamed));
        return new CsvTable(List.copyOf(renamed.values()), out);
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }
}
