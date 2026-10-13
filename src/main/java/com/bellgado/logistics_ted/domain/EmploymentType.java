package com.bellgado.logistics_ted.domain;

/**
 * How a person works for Tedhouse, per the client's staff sheet ({@code Вид служител}): a member of
 * a Tedhouse crew, a firm working for Tedhouse, or a member of such a firm.
 */
public enum EmploymentType {
    /** {@code Вътрешен} — Tedhouse's own crews and their members. */
    INTERNAL,
    /** {@code Външен} — a member of a subcontractor's crew. */
    EXTERNAL,
    /** {@code Подизпълнител} — the firm (its owner/leader) working for Tedhouse. */
    SUBCONTRACTOR
}
