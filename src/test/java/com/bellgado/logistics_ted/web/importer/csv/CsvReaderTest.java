package com.bellgado.logistics_ted.web.importer.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Parser coverage aimed at what Google Sheets and Excel actually emit, not at the RFC in the
 * abstract: BOMs, semicolon exports, trailing blank rows, Cyrillic, and addresses with embedded
 * newlines and commas.
 */
class CsvReaderTest {

    private static CsvTable parse(String text) {
        return CsvReader.parse(text, CsvFormat.defaults());
    }

    @Test
    void parsesHeaderAndRows() {
        CsvTable t = parse("key,name,unit\nMAT-001,ОСБ плоскост,m2\nMAT-002,Гипсокартон,m2\n");

        assertThat(t.headers()).containsExactly("key", "name", "unit");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(0).text("name")).isEqualTo("ОСБ плоскост");
        assertThat(t.rows().get(1).text("key")).isEqualTo("MAT-002");
    }

    @Test
    void headerLookupIsCaseAndWhitespaceInsensitive() {
        CsvTable t = parse(" Key , Name \nH-001,Къща\n");

        assertThat(t.headers()).containsExactly("key", "name");
        assertThat(t.rows().get(0).text("KEY")).isEqualTo("H-001");
        assertThat(t.rows().get(0).has("name")).isTrue();
        assertThat(t.rows().get(0).has("location")).isFalse();
    }

    @Test
    void stripsTheBomExcelAdds() {
        byte[] bytes = ("﻿key,name\nH-001,Къща\n").getBytes(StandardCharsets.UTF_8);
        CsvTable t = CsvReader.read(bytes, CsvFormat.defaults());

        // Without stripping, the first column would be named "﻿key" and never resolve.
        assertThat(t.headers()).containsExactly("key", "name");
        assertThat(t.rows().get(0).text("key")).isEqualTo("H-001");
    }

    @Test
    void quotedFieldsKeepDelimitersAndNewlines() {
        CsvTable t = parse("key,location\nH-001,\"с. Бистрица, ул. Витоша 12\"\n");
        assertThat(t.rows().get(0).text("location")).isEqualTo("с. Бистрица, ул. Витоша 12");

        CsvTable multi = parse("key,notes\nH-001,\"line one\nline two\"\nH-002,x\n");
        assertThat(multi.rows()).hasSize(2);
        assertThat(multi.rows().get(0).text("notes")).isEqualTo("line one\nline two");
    }

    @Test
    void doubledQuotesBecomeOneQuote() {
        CsvTable t = parse("key,name\nH-001,\"Къща \"\"Иванов\"\"\"\n");
        assertThat(t.rows().get(0).text("name")).isEqualTo("Къща \"Иванов\"");
    }

    @Test
    void lineNumbersSurviveQuotedNewlines() {
        // The report must point at the line the user sees in the spreadsheet.
        CsvTable t = parse("key,notes\nH-001,\"a\nb\"\nH-002,c\n");
        assertThat(t.rows().get(0).line()).isEqualTo(2);
        assertThat(t.rows().get(1).line()).isEqualTo(4);
    }

    @Test
    void blankLinesAreDropped() {
        // Sheets exports carry a long tail of empty rows below the real data.
        CsvTable t = parse("key,name\nH-001,Къща\n\n,,\n\nH-002,Друга\n\n\n");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(1).text("key")).isEqualTo("H-002");
    }

    @Test
    void handlesCrlfAndAMissingFinalNewline() {
        CsvTable t = parse("key,name\r\nH-001,Къща\r\nH-002,Друга");
        assertThat(t.rows()).hasSize(2);
        assertThat(t.rows().get(1).text("name")).isEqualTo("Друга");
    }

    @Test
    void semicolonExportsAreSupported() {
        CsvTable t = CsvReader.parse("key;name\nH-001;Къща\n", CsvFormat.of("semicolon", "dot"));
        assertThat(t.rows().get(0).text("name")).isEqualTo("Къща");
    }

    @Test
    void shortRowsReadAsEmptyCells() {
        CsvTable t = parse("key,name,location\nH-001,Къща\n");
        assertThat(t.rows().get(0).text("location")).isNull();
        assertThat(t.rows().get(0).has("location")).isTrue();   // the file still declares the column
    }

    @Test
    void rejectsNonUtf8() {
        byte[] broken = {'k', 'e', 'y', '\n', (byte) 0xC3, (byte) 0x28};

        assertThatThrownBy(() -> CsvReader.read(broken, CsvFormat.defaults()))
            .isInstanceOf(CsvException.class)
            .satisfies(e -> assertThat(((CsvException) e).getCode()).isEqualTo(ImportErrorCode.ENCODING));
    }

    @Test
    void rejectsDuplicateColumns() {
        assertThatThrownBy(() -> parse("key,name,name\nH-001,a,b\n"))
            .isInstanceOf(CsvException.class)
            .satisfies(e -> assertThat(((CsvException) e).getCode()).isEqualTo(ImportErrorCode.DUPLICATE_COLUMN));
    }

    @Test
    void rejectsAnEmptyFile() {
        assertThatThrownBy(() -> parse("\n\n"))
            .isInstanceOf(CsvException.class)
            .satisfies(e -> assertThat(((CsvException) e).getCode()).isEqualTo(ImportErrorCode.NO_HEADER));
    }

    @Test
    void requireColumnsNamesWhatIsMissing() {
        CsvTable t = parse("key,name\nH-001,Къща\n");

        assertThatThrownBy(() -> t.requireColumns("key", "name", "location"))
            .isInstanceOf(CsvException.class)
            .hasMessageContaining("location");
    }

    @Test
    void unknownColumnsAreReportedNotFatal() {
        CsvTable t = parse("key,name,коментар\nH-001,Къща,бележка\n");
        assertThat(t.unknownColumns(java.util.Set.of("key", "name"))).containsExactly("коментар");
    }
}
