package com.bellgado.logistics_ted.service.solver;

/** Per-house inventory line: a material's display metadata plus the stocked quantity. */
public record InventoryEntry(String name, String unit, double quantity) {}
