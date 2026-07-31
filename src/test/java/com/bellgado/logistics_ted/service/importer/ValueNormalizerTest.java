package com.bellgado.logistics_ted.service.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Normalisation is what stops the merge from reporting changes that aren't. Every case here is a
 * false "sheet changed" that would otherwise be written back on every single run — and, when the app
 * had also touched the row, a false conflict.
 */
class ValueNormalizerTest {

    @Test
    void decimalsCompareByValueNotRepresentation() {
        assertThat(ValueNormalizer.normalize("24.50", ColumnType.DECIMAL)).isEqualTo("24.5");
        assertThat(ValueNormalizer.normalize(new BigDecimal("24.500"), ColumnType.DECIMAL)).isEqualTo("24.5");
        assertThat(ValueNormalizer.normalize("24.5", ColumnType.DECIMAL)).isEqualTo("24.5");
        // The DB stores numeric(10,2), so a price entered as "26" comes back as 26.00.
        assertThat(ValueNormalizer.normalize(new BigDecimal("26.00"), ColumnType.DECIMAL))
            .isEqualTo(ValueNormalizer.normalize("26", ColumnType.DECIMAL));
    }

    @Test
    void zeroDecimalsCollapseToOneForm() {
        assertThat(ValueNormalizer.normalize(new BigDecimal("0.00"), ColumnType.DECIMAL))
            .isEqualTo(ValueNormalizer.normalize("0", ColumnType.DECIMAL));
    }

    @Test
    void whitespaceAndNbspAreCollapsed() {
        assertThat(ValueNormalizer.normalize("  Къща   Иванов ", ColumnType.TEXT)).isEqualTo("Къща Иванов");
        // Spreadsheets sprinkle non-breaking spaces into pasted text.
        String withNbsp = "Къща" + (char) 0x00A0 + "Иванов";
        assertThat(ValueNormalizer.normalize(withNbsp, ColumnType.TEXT)).isEqualTo("Къща Иванов");
    }

    @Test
    void blankBecomesNull() {
        assertThat(ValueNormalizer.normalize("", ColumnType.TEXT)).isNull();
        assertThat(ValueNormalizer.normalize("   ", ColumnType.TEXT)).isNull();
        assertThat(ValueNormalizer.normalize(null, ColumnType.TEXT)).isNull();
    }

    @Test
    void pipeSetsAreOrderInsensitiveAndDeduplicated() {
        assertThat(ValueNormalizer.normalize("5|3|4", ColumnType.PIPE_SET)).isEqualTo("3|4|5");
        assertThat(ValueNormalizer.normalize("3|4|5", ColumnType.PIPE_SET)).isEqualTo("3|4|5");
        assertThat(ValueNormalizer.normalize("3| 4 |3|5|", ColumnType.PIPE_SET)).isEqualTo("3|4|5");
    }

    @Test
    void enumsAreCaseInsensitive() {
        assertThat(ValueNormalizer.normalize("in_progress", ColumnType.ENUM)).isEqualTo("IN_PROGRESS");
        assertThat(ValueNormalizer.normalize(" Done ", ColumnType.ENUM)).isEqualTo("DONE");
    }

    @Test
    void datesAcceptTheFormatsSpreadsheetsProduce() {
        assertThat(ValueNormalizer.normalize("2026-03-04", ColumnType.DATE)).isEqualTo("2026-03-04");
        assertThat(ValueNormalizer.normalize("04.03.2026", ColumnType.DATE)).isEqualTo("2026-03-04");
        assertThat(ValueNormalizer.normalize("04/03/2026", ColumnType.DATE)).isEqualTo("2026-03-04");
        assertThat(ValueNormalizer.normalize(LocalDate.of(2026, 3, 4), ColumnType.DATE)).isEqualTo("2026-03-04");
    }

    @Test
    void booleansAcceptBulgarianAndEnglish() {
        assertThat(ValueNormalizer.normalize("да", ColumnType.BOOLEAN)).isEqualTo("true");
        assertThat(ValueNormalizer.normalize("НЕ", ColumnType.BOOLEAN)).isEqualTo("false");
        assertThat(ValueNormalizer.normalize("1", ColumnType.BOOLEAN)).isEqualTo("true");
        assertThat(ValueNormalizer.normalize(Boolean.TRUE, ColumnType.BOOLEAN)).isEqualTo("true");
    }

    @Test
    void unparseableValuesDegradeInsteadOfThrowing() {
        // A single odd legacy value must not be able to abort a whole run.
        assertThat(ValueNormalizer.normalize("n/a", ColumnType.DECIMAL)).isEqualTo("n/a");
        assertThat(ValueNormalizer.normalize("веднага", ColumnType.DATE)).isEqualTo("веднага");
    }

    @Test
    void eqIsNullSafe() {
        assertThat(ValueNormalizer.eq(null, null)).isTrue();
        assertThat(ValueNormalizer.eq(null, "a")).isFalse();
        assertThat(ValueNormalizer.eq("a", null)).isFalse();
        assertThat(ValueNormalizer.eq("a", "a")).isTrue();
    }
}
