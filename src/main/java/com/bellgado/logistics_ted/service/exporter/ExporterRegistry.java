package com.bellgado.logistics_ted.service.exporter;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Every {@link EntityExporter} on the classpath, addressable by name. Registration is by Spring
 * injection, so adding a dataset is one class and no wiring — the same shape as the import's
 * {@code ImporterRegistry}.
 */
@Component
public class ExporterRegistry {

    private final Map<String, EntityExporter<?>> byName = new TreeMap<>();

    public ExporterRegistry(List<EntityExporter<?>> exporters) {
        for (EntityExporter<?> e : exporters) {
            if (byName.put(e.name(), e) != null) {
                throw new IllegalStateException("Two exporters are registered as '" + e.name() + "'.");
            }
        }
    }

    public Optional<EntityExporter<?>> find(String name) {
        return Optional.ofNullable(byName.get(name == null ? "" : name.trim().toLowerCase()));
    }

    /** All datasets, alphabetically. */
    public List<EntityExporter<?>> all() {
        return List.copyOf(byName.values());
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }
}
