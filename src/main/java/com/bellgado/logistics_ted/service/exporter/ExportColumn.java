package com.bellgado.logistics_ted.service.exporter;

import java.util.function.Function;

/**
 * One exportable column of a dataset.
 *
 * @param name            the CSV header and the token a client passes in {@code ?columns=}
 * @param type            how the value is rendered
 * @param description     one line for the catalogue, so a client can pick columns without guessing
 * @param defaultIncluded whether the column is part of the export when no {@code columns} are given
 * @param value           extracts the raw value from a row; {@code null} renders as an empty cell
 */
public record ExportColumn<R>(String name,
                              ExportColumnType type,
                              String description,
                              boolean defaultIncluded,
                              Function<R, Object> value) {

    public static <R> ExportColumn<R> of(String name, ExportColumnType type, String description,
                                         Function<R, Object> value) {
        return new ExportColumn<>(name, type, description, true, value);
    }

    public static <R> ExportColumn<R> optional(String name, ExportColumnType type, String description,
                                               Function<R, Object> value) {
        return new ExportColumn<>(name, type, description, false, value);
    }
}
