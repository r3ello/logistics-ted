# Tedhouse Logistics — Spring Boot

Spring Boot port of the original Node.js POC. Same dashboard, same frontend; PostgreSQL
instead of MySQL. The route-optimization algorithm has since been extended to support real
road distances (Google Routes), supplier fallback, and multi-objective alternatives.

## Stack

- Java 21
- Spring Boot 3.5.14 (pinned — see `CLAUDE.md` for why not 4.x)
- Spring Web MVC + Spring Data JPA + Spring Security (session auth)
- PostgreSQL with Flyway migrations
- Static frontend (HTML + vanilla JS + Leaflet)

## Prerequisites

- Java 21
- Maven (or use the bundled `./mvnw`)
- A PostgreSQL 14+ instance reachable from the host

## Quick start

1. **Point at a PostgreSQL instance** — either set `DB_URL` / `DB_USER` / `DB_PASSWORD`
   env vars, or edit `src/main/resources/application.yaml`. Flyway will create the schema
   and seed data on first start (`V1__schema.sql`, `V2__seed.sql`, `V3__supplier_material.sql`).

2. **Run the application**

   ```sh
   ./mvnw spring-boot:run
   ```

3. **Open** http://localhost:8080

## Default accounts

| Username | Role  |
|----------|-------|
| admin    | Admin |
| user     | User  |

The bcrypt hashes (`$2b$10$…`) were carried over verbatim from the Node MySQL dump and
match against Spring's `BCryptPasswordEncoder` without re-hashing.

## Configuration

Defaults live in `src/main/resources/application.yaml`. Override via env vars:

| Env var                    | Default                       | Notes                                                |
|----------------------------|-------------------------------|------------------------------------------------------|
| `DB_URL`                   | `jdbc:postgresql://...`       | Point at your own Postgres                           |
| `DB_USER`                  | `postgres`                    |                                                      |
| `DB_PASSWORD`              | `postgres`                    |                                                      |
| `SERVER_PORT`              | `3002`                        |                                                      |
| `ROUTING_PROVIDER`         | `google`                      | `google` for real road distances, `haversine` for the in-process fallback |
| `ROUTING_GOOGLE_API_KEY`   | _(committed dev key in yaml)_ | Required when `provider=google`; falls back to haversine if missing or the API call fails |
| `ROUTING_HAVERSINE_KMH`    | `40`                          | Average speed used to estimate minutes when Google is off |
| `ROUTING_BALANCED_ALPHA`   | `1.0`                         | Weight on km for the `balanced` objective            |
| `ROUTING_BALANCED_BETA`    | `1.0`                         | Weight on minutes for the `balanced` objective       |

## Repository layout

```
src/main/java/com/bellgado/logistics_ted/
├── LogisticsTedApplication.java
├── config/             SecurityConfig, RoutingConfig, RoutingProperties
├── security/           Session-auth helpers (UserDetailsService, JSON 401/403 handlers)
├── domain/             JPA entities:
│                         House, Warehouse, Inventory, Material,
│                         Supplier, SupplierInventory, AppUser,
│                         Worker, Scaffold, ScaffoldStatus
├── repository/         Spring Data JPA repositories (one per entity)
├── service/
│   ├── HouseService, InventoryService, ServerMessages
│   ├── RouteOptimizationService, SupplierFallbackService
│   ├── OrderHistoryService
│   ├── distance/       RouteMatrixService, HaversineMatrixService,
│   │                   GoogleRoutesMatrixService, CachingRouteMatrixService
│   └── solver/         RouteSolver, HeuristicRouteSolver, ObjectiveSpec
└── web/
    ├── AuthController, HouseController, InventoryController
    ├── MaterialController, OrderController, OrderHistoryController
    ├── WorkerController          — CRUD for workers
    ├── ScaffoldController        — CRUD for scaffold entities (/api/scaffolds)
    ├── ScaffoldTransportController — transport lookup (/api/scaffold-transport)
    └── dto/                      OrderRequest, OrderResponse, HouseDto,
                                  HouseUpsertRequest, …

src/main/resources/
├── application.yaml
├── db/migration/
│   ├── V1__schema.sql            Base schema (houses, warehouses, inventory, materials, users)
│   ├── V2__seed.sql              Seed data (materials, default accounts)
│   ├── V3__supplier_material.sql Supplier + supplier_inventory tables
│   ├── V4__order_history.sql     customer_order, order_route_option, order_event tables
│   ├── V5__scaffold.sql          scaffold_status column on house
│   ├── V6__scaffold_dates.sql    scaffold_start_date / scaffold_end_date on house
│   ├── V7__workers.sql           worker table (name, location, lat, lng)
│   ├── V8__worker_crew.sql       crew column on worker
│   ├── V9__worker_house.sql      house_id FK on worker
│   └── V10__scaffold_entity.sql  scaffold table (first-class entity, migrates V5/V6 data)
└── static/
    ├── index.html                SPA frontend (vanilla JS + Leaflet, EN/BG i18n)
    └── map-picker.html           Standalone map pin picker (localStorage round-trip)
```

## Database notes

The MySQL schema was ported to PostgreSQL with these adjustments:

- `int AUTO_INCREMENT` → `INTEGER GENERATED BY DEFAULT AS IDENTITY`
- `enum('admin','user')` → `VARCHAR(10)` with a `CHECK` constraint
- `users` table renamed to `app_user` (`users` is reserved in PostgreSQL)
- All `decimal(p,s)` map directly to `NUMERIC(p,s)`
- FK constraints and the `(warehouse_id, material_id)` unique key preserved
- `supplier_inventory` added in `V3` for the supplier-fallback path

## Route optimization

`POST /api/calculate-order` runs a multi-stage pipeline:

1. **Candidate selection** — fetch houses that have any of the needed materials and aren't
   the destination.
2. **Matrix build** — a pairwise `RouteCostMatrix` (km + seconds) over
   `[origin, …relevant houses, destination]`. Provider is pluggable:
   - `provider=google` → real road distances + durations via the Google Routes API. On any
     failure (missing key, network error, incomplete response) it falls back transparently
     to haversine for that request.
   - `provider=haversine` → great-circle distance with minutes estimated from
     `routing.haversine.speed-kmh`.
   - Both go through a process-local TTL cache so repeated requests don't re-fetch.
3. **Heuristic solver** (`HeuristicRouteSolver`):
   - ≤15 relevant houses → exhaustive subset enumeration: every covering subset is scored
     with NN + 2-opt; the smallest wins.
   - \>15 → greedy `quantity / cost` fallback per material.
   - 2-opt is asymmetric-safe (re-scores the reversed interior), needed because Google's
     matrix is directional.
4. **Material allocation** — closest house first per material; shortfalls become `deficit`.
5. **Supplier fallback** — any leftover deficit gets a chance to be covered from
   `supplier_inventory`. Suppliers form their own small matrix anchored at the last picked
   house (or the origin if none was picked) and contribute as additional `supplierStops`.
6. **Multi-objective alternatives** — the whole pipeline runs three times under different
   objectives: `shortest_distance`, `fastest_time`, `balanced(α·km + β·min)`.
7. **Pareto-relabel** — because NN + 2-opt is heuristic and can land in different local
   minima per objective, each label is then reassigned to whichever of the three candidate
   routes actually wins under it. So `fastest_time.totalMinutes` is always ≤
   `shortest_distance.totalMinutes`, and vice versa for km.
8. **Dedup** — alternatives with identical (house ids + supplier ids) sequences collapse to
   a single entry. When all three converge (small problems, or one objective dominates), the
   response carries a single alternative and the frontend hides the option-cards row.

The response shape:

```jsonc
{
  "origin": {...}, "destination": {...},
  "route": [...],            // top-level mirrors the shortest_distance alternative
  "supplierStops": [...],
  "deficit": [...],
  "mapsUrl": "...",
  "fullyFulfilled": true,
  "totalStops": 3,
  "totalDistance": 47,        // km, rounded
  "totalMinutes": 71,         // estimated from matrix.seconds
  "alternatives": [
    { "objective": "shortest_distance", "route": [...], "totalDistance": 47, "totalMinutes": 75, ... },
    { "objective": "fastest_time",      "route": [...], "totalDistance": 51, "totalMinutes": 68, ... }
    // balanced may collapse into one of the above on small problems
  ]
}
```

## Workers

Workers are a first-class entity (`worker` table, added by `V7__workers.sql` and `V8__worker_crew.sql`).

| Field    | Notes |
|----------|-------|
| `name`   | Required |
| `location` | Auto-filled via OpenStreetMap reverse geocoding when a map pin is placed |
| `lat/lng` | Validated: latitude −90..90, longitude −180..180 |
| `crew`   | Trade speciality (Roofing, Plumbing, Electricity, Framing, Finishing, …) |
| `house_id` | Optional FK — which site the worker is currently assigned to (added by `V9__worker_house.sql`) |

**API:** `GET/POST /api/workers`, `PUT/DELETE /api/workers/{id}`

Workers are visible on the dashboard (Workers tab) and on the Map View (Workers / Houses & Workers modes). The worker map popup shows name, location, crew, and assigned house.

## Scaffold Transport

Scaffolds are a first-class entity (`scaffold` table, added by `V10__scaffold_entity.sql`).
Each scaffold has a `status` (`NONE` / `AVAILABLE` / `IN_USE`), optional `start_date` / `end_date`,
and an optional `house_id` FK. One house can have at most one scaffold assigned.

**API:** `GET/POST /api/scaffolds`, `PUT/DELETE /api/scaffolds/{id}`

Validations:
- Duplicate house assignment is rejected (409-style 400).
- End date must be on or after start date.

### Scaffold Transport menu — 🏗️

`GET /api/scaffold-transport?destinationHouseId={id}&startLat={lat}&startLng={lng}`

Finds the closest scaffold with `status = AVAILABLE` to the **destination** house (not the
driver). Driver coordinates are optional; when supplied, the Google Maps URL routes
driver → scaffold pickup → destination.

Special cases:
- Destination house already has an `AVAILABLE` scaffold → returns `alreadyAvailable: true`.
- No available scaffold found → returns `scaffoldHouse: null`.

The scaffold form has its own independent driver-location picker separate from the order form.

## Dashboard — three-mode toggle

The dashboard has a toggle at the top: **Houses | Workers | Scaffold**.

| Mode | Content |
|------|---------|
| Houses | Original house cards with materials, stock, phase chips + scaffold status chip |
| Workers | Worker cards with crew, assigned house; Add/Edit/Delete; search bar |
| Scaffold | Scaffold cards with status, assigned house, dates; Add/Edit/Delete; search bar |

## Map View — four-mode toggle

| Mode | Description |
|------|-------------|
| 🏠 Houses | Standard house markers with materials popup |
| 🏗️ Scaffold | Markers coloured by scaffold status; popup shows scaffold info; legend at bottom |
| 👷 Workers | Worker emoji markers with crew and assigned house popup |
| 🏠👷 Houses & Workers | Both layers — houses (green labels + full popup), workers (orange labels + popup) |

## Reverse geocoding

When a map pin is placed in the house modal or the worker modal, the app calls the
OpenStreetMap Nominatim API (`/reverse`) to auto-fill the Location field with a human-readable
city/country name. No API key required.

## Internationalisation

All UI strings are in the `i18n` object (`en` / `bg`). The language toggle re-renders
material field labels, static form labels, modal options, map toggle buttons, dashboard
chips, and map popup rows without losing entered values.

## Logging

The route pipeline emits an INFO-level breadcrumb at every stage so you can see what
happened on each request: provider used, matrix dimensions, per-objective km/min, when a
relabel kicks in, and how alternatives dedupe.

For per-pair cache and per-subset solver detail, flip the relevant logger to `DEBUG` in
`application.yaml` (commented hints are inline).

## Tests

Algorithm goldens and unit tests run without a database:

```sh
./mvnw -Dtest=RouteOptimizationServiceTest,RouteOptimizationServiceTwoOptTest,RouteCostCacheTest,CachingRouteMatrixServiceTest,GoogleRoutesMatrixServiceTest,SupplierFallbackServiceTest test
```

The full `./mvnw verify` build expects Postgres to be reachable (the `@SpringBootTest`
slice opens a real Spring context).

