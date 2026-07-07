package com.bellgado.logistics_ted.web.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.bellgado.logistics_ted.web.audit.AuditActionResolver.ResolvedAction;
import org.junit.jupiter.api.Test;

/**
 * Table-driven coverage of the path→(action, entity, id) derivation over the full mutating
 * endpoint inventory, plus the graceful-fallback rule for unknown future endpoints.
 */
class AuditActionResolverTest {

    private static final String UUID = "3f1c9a52-7b0e-4d2c-9a41-8c6f2e5b1d07";

    private final AuditActionResolver resolver = new AuditActionResolver();

    private void assertResolved(String method, String path,
                                String action, String entity, String entityId) {
        ResolvedAction r = resolver.resolve(method, path);
        assertThat(r.action()).as("action of %s %s", method, path).isEqualTo(action);
        assertThat(r.entityType()).as("entity of %s %s", method, path).isEqualTo(entity);
        assertThat(r.entityId()).as("entityId of %s %s", method, path).isEqualTo(entityId);
    }

    @Test
    void houses() {
        assertResolved("POST",   "/api/houses",              "create", "house", null);
        assertResolved("PUT",    "/api/houses/12",           "update", "house", "12");
        assertResolved("DELETE", "/api/houses/12",           "delete", "house", "12");
        assertResolved("PATCH",  "/api/houses/12/scaffold",  "update", "house_scaffold", "12");
        assertResolved("PUT",    "/api/houses/12/inventory", "update", "house_inventory", "12");
    }

    @Test
    void crewsAndMembers() {
        assertResolved("POST",   "/api/crews",             "create", "crew", null);
        assertResolved("PUT",    "/api/crews/3",           "update", "crew", "3");
        assertResolved("DELETE", "/api/crews/3",           "delete", "crew", "3");
        assertResolved("POST",   "/api/crews/3/members/9", "create", "crew_member", "3");
        assertResolved("DELETE", "/api/crews/3/members/9", "delete", "crew_member", "3");
    }

    @Test
    void workersAndCredentials() {
        assertResolved("POST",   "/api/workers",               "create", "worker", null);
        assertResolved("PUT",    "/api/workers/5",             "update", "worker", "5");
        assertResolved("DELETE", "/api/workers/5",             "delete", "worker", "5");
        assertResolved("PUT",    "/api/workers/5/credentials", "update", "worker_credential", "5");
        assertResolved("PUT",    "/api/workers/5/password",    "update", "worker_password", "5");
    }

    @Test
    void scaffolds() {
        assertResolved("POST",   "/api/scaffolds",   "create", "scaffold", null);
        assertResolved("PUT",    "/api/scaffolds/2", "update", "scaffold", "2");
        assertResolved("DELETE", "/api/scaffolds/2", "delete", "scaffold", "2");
    }

    @Test
    void materialOrdersAndDeliveries() {
        assertResolved("POST",   "/api/material-orders",              "create", "material_order", null);
        assertResolved("PUT",    "/api/material-orders/7",            "update", "material_order", "7");
        assertResolved("DELETE", "/api/material-orders/7",            "delete", "material_order", "7");
        assertResolved("POST",   "/api/material-orders/7/deliveries", "create", "material_order_delivery", "7");
        assertResolved("DELETE", "/api/deliveries/4",                 "delete", "delivery", "4");
    }

    @Test
    void facilitiesWithInventory() {
        assertResolved("POST", "/api/warehouses",             "create", "warehouse", null);
        assertResolved("PUT",  "/api/warehouses/5/inventory", "update", "warehouse_inventory", "5");
        assertResolved("POST", "/api/suppliers",              "create", "supplier", null);
        assertResolved("PUT",  "/api/suppliers/6/inventory",  "update", "supplier_inventory", "6");
    }

    @Test
    void electricBoxesAndStages() {
        assertResolved("POST",   "/api/electric-boxes",   "create", "electric_box", null);
        assertResolved("PUT",    "/api/electric-boxes/3", "update", "electric_box", "3");
        assertResolved("POST",   "/api/stage-types",      "create", "stage_type", null);
        assertResolved("PUT",    "/api/stage-types/2",    "update", "stage_type", "2");
        assertResolved("DELETE", "/api/stage-types/2",    "delete", "stage_type", "2");
        assertResolved("PUT",    "/api/house-stages/9",   "update", "house_stage", "9");
    }

    @Test
    void orderRoutes() {
        assertResolved("POST", "/api/calculate-order", "calculate", "order", null);
        assertResolved("POST", "/api/orders/" + UUID + "/options/balanced/view", "view", "order", UUID);
        assertResolved("POST", "/api/orders/" + UUID + "/choose", "choose", "order", UUID);
    }

    @Test
    void myScopeIsDropped() {
        assertResolved("POST", "/api/my/material-orders", "create", "material_order", null);
        assertResolved("POST", "/api/my/stages/5/start",  "start",  "house_stage", "5");
        assertResolved("POST", "/api/my/stages/5/finish", "finish", "house_stage", "5");
    }

    @Test
    void checkinTokenIsNeverCaptured() {
        ResolvedAction in = resolver.resolve("POST", "/api/public/checkin/" + UUID + "/session");
        assertThat(in).isEqualTo(new ResolvedAction("check_in", "work_session", null));

        ResolvedAction out = resolver.resolve("PUT", "/api/public/checkin/" + UUID + "/session/8/checkout");
        assertThat(out).isEqualTo(new ResolvedAction("check_out", "work_session", "8"));
    }

    @Test
    void crewLoginsViaGenericRule() {
        assertResolved("POST", "/api/crew-logins", "create", "crew_login", null);
    }

    @Test
    void unknownEndpointsDegradeGracefully() {
        assertResolved("DELETE", "/api/frobnicators/33", "delete", "frobnicator", "33");
        assertResolved("POST",   "/api/gadget-parts",    "create", "gadget_part", null);
        assertResolved("POST",   "/api",                 "create", null, null);
    }
}
