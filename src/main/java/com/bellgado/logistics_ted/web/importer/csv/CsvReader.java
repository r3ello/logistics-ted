package com.bellgado.logistics_ted.web.importer.csv;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RFC 4180 reader tuned for Google Sheets exports.
 *
 * <p>Deliberately hand-rolled rather than pulling in a CSV library: the parsing rules we need are
 * small and the project keeps its dependency surface tight to stay off Spring Boot 4 transitives
 * (see CLAUDE.md). The behaviour that actually matters here is the spreadsheet-specific part:
 *
 * <ul>
 *   <li><b>Strict UTF-8.</b> The data is Cyrillic; a file in another encoding is rejected outright
 *       rather than transcoded on a guess, which would produce plausible-looking mojibake that then
 *       gets written to the database.</li>
 *   <li><b>BOM stripped.</b> Excel on Windows adds one, and it would otherwise become part of the
 *       first header name, making every lookup of that column miss.</li>
 *   <li><b>Blank lines dropped.</b> Sheets exports are full of them, including a long tail below the
 *       real data.</li>
 *   <li><b>Embedded newlines honoured</b> inside quoted fields — addresses and notes contain them —
 *       while line numbers still point at the physical line a record starts on, so the error report
 *       matches what the user sees in the spreadsheet.</li>
 * </ul>
 *
 * <p>Lenient where leniency is safe: a stray quote in the middle of an unquoted field is kept
 * literally instead of failing the file, and short rows read as empty cells rather than errors.
 */
public final class CsvReader {

    private CsvReader() {}

    /** Guard against an accidental multi-hundred-MB paste; the report says so explicitly. */
    public static final int MAX_ROWS = 50_000;

    private static final char BOM = 0xFEFF;

    public static CsvTable read(InputStream in, CsvFormat format) {
        try {
            return read(in.readAllBytes(), format);
        } catch (IOException e) {
            throw new CsvException(ImportErrorCode.MALFORMED_CSV, "Could not read the uploaded file.", e);
        }
    }

    public static CsvTable read(byte[] bytes, CsvFormat format) {
        return parse(decodeUtf8(bytes), format);
    }

    public static CsvTable parse(String text, CsvFormat format) {
        List<Record> records = new Parser(text, format.delimiter()).parse();
        if (records.isEmpty()) {
            throw new CsvException(ImportErrorCode.NO_HEADER, "The file is empty — a header row is required.");
        }

        Record headerRecord = records.get(0);
        Map<String, Integer> index = new LinkedHashMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < headerRecord.fields().length; i++) {
            String name = headerRecord.fields()[i].trim().toLowerCase();
            if (name.isEmpty()) continue;          // trailing empty columns are normal in exports
            if (index.putIfAbsent(name, i) != null) duplicates.add(name);
            else headers.add(name);
        }
        if (!duplicates.isEmpty()) {
            throw new CsvException(ImportErrorCode.DUPLICATE_COLUMN,
                "Duplicate column(s) in the header: " + String.join(", ", duplicates) + ".");
        }
        if (headers.isEmpty()) {
            throw new CsvException(ImportErrorCode.NO_HEADER, "The first row has no usable column names.");
        }

        int dataCount = records.size() - 1;
        if (dataCount > MAX_ROWS) {
            throw new CsvException(ImportErrorCode.ROW_LIMIT_EXCEEDED,
                "The file has " + dataCount + " rows; the limit is " + MAX_ROWS + " per import.");
        }

        List<CsvRow> rows = new ArrayList<>(dataCount);
        for (int i = 1; i < records.size(); i++) {
            Record r = records.get(i);
            rows.add(new CsvRow(index, r.fields(), r.line(), format));
        }
        return new CsvTable(List.copyOf(headers), List.copyOf(rows));
    }

    // ── decoding ──────────────────────────────────────────────────────────────

    private static String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        String text;
        try {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new CsvException(ImportErrorCode.ENCODING,
                "The file is not valid UTF-8. Re-export it as CSV UTF-8 — Cyrillic text in another "
                    + "encoding cannot be recovered reliably.", e);
        }
        return (!text.isEmpty() && text.charAt(0) == BOM) ? text.substring(1) : text;
    }

    // ── RFC 4180 state machine ────────────────────────────────────────────────

    private record Record(int line, String[] fields) {}

    private static final class Parser {

        private final String text;
        private final char delimiter;
        private final List<Record> records = new ArrayList<>();
        private final List<String> current = new ArrayList<>();
        private final StringBuilder field = new StringBuilder();

        private int line = 1;
        private int recordLine = 1;
        private boolean inQuotes;

        Parser(String text, char delimiter) {
            this.text = text;
            this.delimiter = delimiter;
        }

        List<Record> parse() {
            int n = text.length();
            for (int i = 0; i < n; i++) {
                char c = text.charAt(i);

                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < n && text.charAt(i + 1) == '"') {
                            field.append('"');           // escaped quote
                            i++;
                        } else {
                            inQuotes = false;
                        }
                    } else {
                        if (c == '\n') line++;           // newline inside a quoted cell
                        field.append(c);
                    }
                    continue;
                }

                if (c == '"' && field.isEmpty()) {
                    inQuotes = true;
                } else if (c == delimiter) {
                    endField();
                } else if (c == '\r') {
                    if (i + 1 < n && text.charAt(i + 1) == '\n') i++;
                    endRecord();
                } else if (c == '\n') {
                    endRecord();
                } else {
                    field.append(c);
                }
            }
            if (!field.isEmpty() || !current.isEmpty()) endRecord();
            return records;
        }

        private void endField() {
            current.add(field.toString());
            field.setLength(0);
        }

        private void endRecord() {
            endField();
            if (!isBlank(current)) {
                records.add(new Record(recordLine, current.toArray(new String[0])));
            }
            current.clear();
            line++;
            recordLine = line;
        }

        private static boolean isBlank(List<String> fields) {
            for (String f : fields) {
                if (f != null && !f.isBlank()) return false;
            }
            return true;
        }
    }
}
