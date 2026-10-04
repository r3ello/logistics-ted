package com.bellgado.logistics_ted.service.importer.impl;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How one stage cell of the client's ACTIVE_MASTER sheet reads. The sheet has no structure — each
 * cell is free text, usually "&lt;worker&gt; &lt;status&gt;" in Bulgarian — so this is the single place
 * that decides what a cell means. Rules (agreed with the user, 2026-10-04):
 *
 * <ul>
 *   <li>empty → {@code NOT_STARTED}, no worker</li>
 *   <li>{@code Не} ("not applicable") → <b>untouched</b>: nothing is managed for this stage</li>
 *   <li>"&lt;name&gt; Завършен / изпълнил / Монтирана / Взети размери" → {@code DONE}</li>
 *   <li>"&lt;name&gt; в Процес" → {@code IN_PROGRESS}</li>
 *   <li>just a name ("Жоро", "Слави Р") → {@code ASSIGNED} to that worker</li>
 *   <li>anything else ("Междинен", "Стандарт", "От клиент", "vrati-yukka.bg") → a note; status and
 *       worker untouched</li>
 * </ul>
 *
 * <p>A {@code null} field means "not managed by this cell" (left as it is in the app), which is
 * different from {@link #CLEARED}: an explicit empty value the merge will apply.
 *
 * @param status {@code NOT_STARTED}/{@code ASSIGNED}/{@code IN_PROGRESS}/{@code DONE}, or null
 * @param worker the worker text, {@link #CLEARED}, or null
 * @param note   the note text, or null
 */
public record StageCell(String status, String worker, String note) {

    /** Marks a field the cell explicitly empties (as opposed to null = not managed). */
    public static final String CLEARED = "";

    static final StageCell UNTOUCHED = new StageCell(null, null, null);

    private static final Pattern WITH_STATUS = Pattern.compile(
        "^(.*?)\\s*(завършен[аио]?|в процес|взети размери|монтиран[аио]?|изпълнил[аио]?|изпълнен[аио]?)$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** One to three words of letters ("Жоро", "Слави Р", "Лазар Саламандър"), a trailing dot allowed. */
    private static final Pattern NAME = Pattern.compile("^\\p{L}+\\.?(\\s+\\p{L}+\\.?){0,2}$");

    /** Values that look like a name but are a level/type or a source, not a worker. */
    private static final Set<String> NOT_NAMES = Set.of("междинен", "стандарт", "от клиент");

    public static StageCell parse(String raw) {
        String cell = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (cell.isEmpty()) return new StageCell("NOT_STARTED", CLEARED, null);

        String lower = cell.toLowerCase(Locale.ROOT);
        if (lower.equals("не")) return UNTOUCHED;

        Matcher m = WITH_STATUS.matcher(cell);
        if (m.matches()) {
            String word = m.group(2).toLowerCase(Locale.ROOT);
            String status = word.equals("в процес") ? "IN_PROGRESS" : "DONE";
            String worker = m.group(1).trim();
            // "Завършен" with no name says nothing about who — leave the worker alone.
            return new StageCell(status, worker.isEmpty() ? null : worker, null);
        }
        if (!NOT_NAMES.contains(lower) && NAME.matcher(cell).matches()) {
            return new StageCell("ASSIGNED", cell, null);
        }
        return new StageCell(null, null, cell);
    }

    public boolean isUntouched() {
        return status == null && worker == null && note == null;
    }
}
