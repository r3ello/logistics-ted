package com.bellgado.logistics_ted.service.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Every {@link EntityImporter} on the classpath, addressable by name and ordered so that a file's
 * dependencies are always applied before it.
 *
 * <p>Registration is by Spring injection, so adding an importer is one class and no wiring — and the
 * {@code /api/import/entities} catalogue, being derived from the same objects, cannot describe an
 * importer that does not exist or miss one that does.
 */
@Component
public class ImporterRegistry {

    private final Map<String, EntityImporter> byName = new LinkedHashMap<>();

    public ImporterRegistry(List<EntityImporter> importers) {
        for (EntityImporter i : importers) {
            byName.put(i.name(), i);
        }
    }

    public Optional<EntityImporter> find(String name) {
        return Optional.ofNullable(byName.get(name == null ? "" : name.trim().toLowerCase()));
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /**
     * Topological order over {@link EntityImporter#dependsOn()}. Cycles cannot occur between files —
     * the one real cycle in the schema (worker ↔ crew) is broken by a separate join file, not by
     * ordering — so an unresolvable remainder is a wiring bug and is appended rather than hidden.
     */
    public List<EntityImporter> inDependencyOrder() {
        List<EntityImporter> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        List<EntityImporter> pending = new ArrayList<>(byName.values());

        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (var it = pending.iterator(); it.hasNext(); ) {
                EntityImporter candidate = it.next();
                if (placed.containsAll(candidate.dependsOn())) {
                    ordered.add(candidate);
                    placed.add(candidate.name());
                    it.remove();
                    progress = true;
                }
            }
        }
        ordered.addAll(pending);
        return ordered;
    }
}
