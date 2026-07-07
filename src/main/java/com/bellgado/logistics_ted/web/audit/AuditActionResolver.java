package com.bellgado.logistics_ted.web.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives a human-scannable (action, entityType, entityId) triple from an HTTP method and an
 * {@code /api/**} path, so the bitácora shows "delete house 12" instead of raw URLs. Pure —
 * no Spring imports — and deliberately lenient: unknown future endpoints degrade to the
 * generic method→action / first-segment→entity rule instead of failing.
 */
public final class AuditActionResolver {

    public record ResolvedAction(String action, String entityType, String entityId) {}

    private static final Pattern UUID_RX = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern NUMERIC_RX = Pattern.compile("\\d+");

    private static final Map<String, String> ENTITY_BY_SEGMENT = Map.ofEntries(
        Map.entry("houses", "house"),
        Map.entry("crews", "crew"),
        Map.entry("workers", "worker"),
        Map.entry("scaffolds", "scaffold"),
        Map.entry("material-orders", "material_order"),
        Map.entry("deliveries", "delivery"),
        Map.entry("warehouses", "warehouse"),
        Map.entry("suppliers", "supplier"),
        Map.entry("electric-boxes", "electric_box"),
        Map.entry("stage-types", "stage_type"),
        Map.entry("house-stages", "house_stage"),
        Map.entry("stages", "house_stage"),
        Map.entry("orders", "order"),
        Map.entry("crew-logins", "crew_login"));

    /** Trailing path segments that name the operation rather than a subresource. */
    private static final Set<String> VERB_SEGMENTS = Set.of("start", "finish", "view", "choose");

    public ResolvedAction resolve(String method, String path) {
        String defaultAction = defaultAction(method);
        List<String> segs = split(path);
        if (segs.isEmpty()) {
            return new ResolvedAction(defaultAction, null, null);
        }

        // Worker attendance check-in/out. The {token} segment is a capability token and must
        // never be persisted; the only id worth keeping is the session id on checkout.
        if (segs.size() >= 4 && "public".equals(segs.get(0)) && "checkin".equals(segs.get(1))
                && "session".equals(segs.get(3))) {
            if (segs.size() >= 6 && "checkout".equals(segs.get(5))) {
                return new ResolvedAction("check_out", "work_session", segs.get(4));
            }
            return new ResolvedAction("check_in", "work_session", null);
        }

        // Scope prefixes carry no entity information — drop and continue generically.
        if ("public".equals(segs.get(0)) || "my".equals(segs.get(0))) {
            segs = segs.subList(1, segs.size());
            if (segs.isEmpty()) {
                return new ResolvedAction(defaultAction, null, null);
            }
        }

        if ("calculate-order".equals(segs.get(0))) {
            return new ResolvedAction("calculate", "order", null);
        }

        // orders/{uuid}/options/{objective}/view and orders/{uuid}/choose — the objective is
        // not an entity, so the generic suffix rule would mangle these.
        if ("orders".equals(segs.get(0)) && segs.size() >= 3) {
            String last = segs.get(segs.size() - 1);
            if ("view".equals(last) || "choose".equals(last)) {
                return new ResolvedAction(last, "order", isId(segs.get(1)) ? segs.get(1) : null);
            }
        }

        String action = defaultAction;
        String entityId = null;
        StringBuilder entity = new StringBuilder(entityOf(segs.get(0)));
        for (int i = 1; i < segs.size(); i++) {
            String s = segs.get(i);
            if (isId(s)) {
                if (entityId == null) entityId = s;
            } else if (i == segs.size() - 1 && VERB_SEGMENTS.contains(s)) {
                action = s;
            } else {
                entity.append('_').append(singularize(s.replace('-', '_')));
            }
        }
        return new ResolvedAction(action, entity.toString(), entityId);
    }

    private static String defaultAction(String method) {
        return switch (method == null ? "" : method.toUpperCase()) {
            case "POST" -> "create";
            case "PUT", "PATCH" -> "update";
            case "DELETE" -> "delete";
            default -> method == null ? "unknown" : method.toLowerCase();
        };
    }

    private static List<String> split(String path) {
        if (path == null) return List.of();
        List<String> segs = new ArrayList<>();
        for (String s : path.split("/")) {
            if (!s.isBlank()) segs.add(s);
        }
        if (!segs.isEmpty() && "api".equals(segs.get(0))) {
            segs.remove(0);
        }
        return segs;
    }

    private static String entityOf(String collectionSegment) {
        String mapped = ENTITY_BY_SEGMENT.get(collectionSegment);
        return mapped != null ? mapped : singularize(collectionSegment.replace('-', '_'));
    }

    private static String singularize(String s) {
        if (s.endsWith("ies") && s.length() > 3) return s.substring(0, s.length() - 3) + "y";
        if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 1) return s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean isId(String s) {
        return NUMERIC_RX.matcher(s).matches() || UUID_RX.matcher(s).matches();
    }
}
