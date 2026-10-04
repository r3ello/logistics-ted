package com.bellgado.logistics_ted.service.importer.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every shape of stage cell found in the client's ACTIVE_MASTER sheet (2026-10-04). */
class StageCellTest {

    @ParameterizedTest(name = "[{index}] ''{0}''")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        // cell                          | status       | worker              | note
        "Влади Завършен                  | DONE         | Влади               | NULL",
        "Слави Р Завършен                | DONE         | Слави Р             | NULL",
        "Паскалев изпълнил               | DONE         | Паскалев            | NULL",
        "Етиком Монтирана                | DONE         | Етиком              | NULL",
        "Лазар Саламандър Взети размери  | DONE         | Лазар Саламандър    | NULL",
        "Ради в Процес                   | IN_PROGRESS  | Ради                | NULL",
        "Благо в процес                  | IN_PROGRESS  | Благо               | NULL",
        "' Завършен'                     | DONE         | NULL                | NULL",
        "Жоро                            | ASSIGNED     | Жоро                | NULL",
        "Слави В                         | ASSIGNED     | Слави В             | NULL",
        "Междинен                        | NULL         | NULL                | Междинен",
        "Стандарт                        | NULL         | NULL                | Стандарт",
        "От клиент                       | NULL         | NULL                | От клиент",
        "vrati-yukka.bg                  | NULL         | NULL                | vrati-yukka.bg",
    })
    void readsTheSheetVocabulary(String cell, String status, String worker, String note) {
        StageCell c = StageCell.parse(cell);
        assertThat(c.status()).isEqualTo(status);
        assertThat(c.worker()).isEqualTo(worker);
        assertThat(c.note()).isEqualTo(note);
    }

    @ParameterizedTest
    @CsvSource({"Не", "не", "' Не '"})
    void notApplicableLeavesTheStageAlone(String cell) {
        assertThat(StageCell.parse(cell).isUntouched()).isTrue();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {"NULL", "''", "'   '"})
    void anEmptyCellMeansNotStartedAndNoWorker(String cell) {
        StageCell c = StageCell.parse(cell);
        assertThat(c.status()).isEqualTo("NOT_STARTED");
        assertThat(c.worker()).isEqualTo(StageCell.CLEARED);
        assertThat(c.note()).isNull();
    }
}
