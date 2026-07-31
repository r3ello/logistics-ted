package com.bellgado.logistics_ted.web.importer.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bellgado.logistics_ted.service.importer.ImportErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Typed cell access. The point of these accessors is that a bad cell becomes a row-addressable
 * {@code FAILED} entry in the report rather than a constraint violation from PostgreSQL three steps
 * later, when the offending line is no longer identifiable.
 */
class CsvRowTest {

    /** Two columns so an empty second cell is still a row — a lone empty cell is a blank line. */
    private static CsvRow row(String column, String value) {
        return CsvReader.parse("key," + column + "\nH-001," + value + "\n", CsvFormat.defaults())
                        .rows().get(0);
    }

    /**
     * The realistic Bulgarian-locale export: semicolon-delimited, comma decimals. A comma-decimal
     * file delimited by commas is unparseable by construction — which is why Excel switches to ';'.
     */
    private static CsvRow bgRow(String column, String value) {
        return CsvReader.parse("key;" + column + "\nH-001;" + value + "\n", CsvFormat.of("semicolon", "comma"))
                        .rows().get(0);
    }

    @Test
    void decimalsParseWithDotSeparator() {
        assertThat(row("price", "24.50").decimal("price")).isEqualByComparingTo("24.50");
        assertThat(row("price", "").decimal("price")).isNull();
    }

    @Test
    void decimalsParseWithCommaSeparatorWhenAsked() {
        assertThat(bgRow("lat", "42,6977").decimal("lat")).isEqualByComparingTo("42.6977");
        assertThat(bgRow("lat", "").decimal("lat")).isNull();
    }

    @Test
    void commaInDotModeIsRejectedRatherThanGuessed() {
        // Stripping it as a grouping separator would silently turn 42,6977 into 426977 — a
        // coordinate in the wrong hemisphere, with nothing in the report to show for it.
        CsvRow r = CsvReader.parse("key;lat\nH-001;42,6977\n", CsvFormat.of("semicolon", "dot"))
                            .rows().get(0);

        assertThatThrownBy(() -> r.decimal("lat"))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> {
                CsvValueException ex = (CsvValueException) e;
                assertThat(ex.getCode()).isEqualTo(ImportErrorCode.INVALID_NUMBER);
                assertThat(ex.getColumn()).isEqualTo("lat");
                assertThat(ex.getValue()).isEqualTo("42,6977");
                assertThat(ex.getLine()).isEqualTo(2);
                assertThat(ex.getMessage()).contains("decimal=comma");
            });
    }

    @Test
    void dotInCommaModeIsRejectedToo() {
        assertThatThrownBy(() -> bgRow("lat", "42.6977").decimal("lat"))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.INVALID_NUMBER));
    }

    @Test
    void coordinatesOutsideTheValidRangeAreRejected() {
        // The DB has no CHECK on lat/lng, so a bad coordinate would silently degrade routing.
        assertThatThrownBy(() -> row("lat", "142.5").decimal("lat", -90, 90))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.OUT_OF_RANGE));

        assertThat(row("lat", "42.6").decimal("lat", -90, 90)).isEqualByComparingTo(new BigDecimal("42.6"));
    }

    @Test
    void datesAcceptSpreadsheetFormats() {
        assertThat(row("d", "2026-03-04").date("d")).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(row("d", "04.03.2026").date("d")).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(row("d", "").date("d")).isNull();

        assertThatThrownBy(() -> row("d", "веднага").date("d"))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.INVALID_DATE));
    }

    @Test
    void enumsAreValidatedAgainstTheDbCheckConstraint() {
        Set<String> allowed = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE");
        assertThat(row("status", "in_progress").enumOf("status", allowed)).isEqualTo("IN_PROGRESS");

        assertThatThrownBy(() -> row("status", "STARTED").enumOf("status", allowed))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> {
                assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.INVALID_ENUM);
                assertThat(e.getMessage()).contains("DONE", "IN_PROGRESS", "NOT_STARTED");
            });
    }

    @Test
    void requiredTextNamesTheMissingColumn() {
        assertThatThrownBy(() -> row("name", "").requiredText("name"))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.EMPTY_REQUIRED));
    }

    @Test
    void lengthLimitsMatchTheTargetColumn() {
        String tooLong = "x".repeat(151);
        assertThatThrownBy(() -> row("name", tooLong).text("name", 150))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> {
                assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.TOO_LONG);
                assertThat(e.getMessage()).contains("151").contains("150");
            });
    }

    @Test
    void pipeListsSplitTrimAndDropBlanks() {
        assertThat(row("stage_orders", "3| 4 ||5").pipeList("stage_orders"))
            .containsExactly("3", "4", "5");
        assertThat(row("stage_orders", "").pipeList("stage_orders")).isEmpty();
    }

    @Test
    void booleansAcceptBulgarian() {
        assertThat(row("has_crew", "да").bool("has_crew")).isTrue();
        assertThat(row("has_crew", "не").bool("has_crew")).isFalse();

        assertThatThrownBy(() -> row("has_crew", "може би").bool("has_crew"))
            .isInstanceOf(CsvValueException.class)
            .satisfies(e -> assertThat(((CsvValueException) e).getCode()).isEqualTo(ImportErrorCode.INVALID_BOOLEAN));
    }

    @Test
    void absentColumnIsDistinctFromEmptyCell() {
        CsvRow r = row("notes", "");
        assertThat(r.has("notes")).isTrue();       // the file manages the field
        assertThat(r.text("notes")).isNull();      // ...and the cell is empty, which is a value
        assertThat(r.has("location")).isFalse();   // the file does not manage this one at all
    }
}
