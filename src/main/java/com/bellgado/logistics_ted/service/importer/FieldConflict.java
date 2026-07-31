package com.bellgado.logistics_ted.service.importer;

/**
 * One column that moved on both sides since the last sync, with all three values so a human can
 * decide. All values are already normalised, so what is shown is what was compared.
 *
 * @param column the CSV column name
 * @param base   what the last successful sync wrote
 * @param app    what the database holds now
 * @param sheet  what the incoming CSV brings
 */
public record FieldConflict(String column, String base, String app, String sheet) {}
