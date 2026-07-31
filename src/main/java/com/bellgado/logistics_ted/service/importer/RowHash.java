package com.bellgado.logistics_ted.service.importer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 over a row's canonical values — the cheap "did anything move?" check stored in
 * {@code import_ref.source_hash}.
 *
 * <p>The snapshot beside it answers the harder question (which column, and what did it hold), but a
 * hash lets a steady-state run fingerprint a row without a field-by-field walk, and gives the report
 * something stable to show.
 */
public final class RowHash {

    private RowHash() {}

    /** ASCII unit/record separators — chosen because they cannot occur in spreadsheet cell text. */
    private static final char FIELD_SEP = 0x1F;
    private static final char RECORD_SEP = 0x1E;

    /** Order-independent: the map is sorted first, so column order in the file cannot affect it. */
    public static String of(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(values).entrySet()) {
            sb.append(e.getKey()).append(FIELD_SEP)
              .append(e.getValue() == null ? "" : e.getValue())
              .append(RECORD_SEP);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
