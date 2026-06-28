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
│                         Worker, WorkerRole, Crew, Scaffold, ScaffoldStatus
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
    ├── WorkerController          — CRUD for workers (/api/workers)
    ├── CrewController            — CRUD for crews (/api/crews)
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
│   ├── V10__scaffold_entity.sql  scaffold table (first-class entity, migrates V5/V6 data)
│   ├── V11__worker_role.sql      worker role enum (CREW_MANAGER / CREW_LEADER / CREW_MEMBER)
│   ├── V12__crew_entity.sql      crew table with manager_id FK
│   ├── V13__manager_no_trade.sql strip trade from all CREW_MANAGER rows
│   ├── V14__crew_members_seed.sql seed 5+ CREW_MEMBERs per crew
│   ├── V15__crew_house.sql       house_id FK on crew (which house the crew works on)
│   ├── V16__crew_worker_coords.sql assign lat/lng to all crew workers near their house city
│   ├── V17__fix_crew_members.sql fix Beta leader assignment, add Delta/Zeta missing members
│   ├── V18__electric_box.sql     electric_box + electric_circuit tables
│   ├── V19__worker_locations.sql assign lat/lng to remaining workers
│   ├── V20–V22                   scaffold seed data + cleanup
│   ├── V23__fix_crew_house_assignments.sql correct crew↔house links
│   ├── V24__house_checkin_token.sql checkin_token UUID column on house
│   ├── V25__work_session.sql     work_session table (check-in/out records)
│   ├── V26__test_house_crew_worker.sql test seed data
│   ├── V27__depot.sql            depot + depot_inventory tables
│   ├── V28–V30                   depot/supplier seed data
│   └── V31__worker_house_from_crew_only.sql null out legacy direct worker.house_id assignments
└── static/
    ├── index.html                SPA frontend (vanilla JS + Leaflet, EN/BG i18n)
    ├── checkin.html              Public check-in/out page (QR-code linked, no auth)
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

## Workers & Crews

### Workers

Workers are a first-class entity (`worker` table, `V7–V9`).

| Field      | Notes |
|------------|-------|
| `name`     | Required |
| `role`     | `CREW_MANAGER`, `CREW_LEADER`, or `CREW_MEMBER` (added by `V11`) |
| `trade`    | Speciality (Roofing, Plumbing, Electricity, Framing, Finishing, …) — CREW_MANAGERs have no trade |
| `location` | Auto-filled via OpenStreetMap reverse geocoding when a map pin is placed |
| `lat/lng`  | Validated: latitude −90..90, longitude −180..180 |
| `crew_id`  | FK to the crew the worker belongs to |
| `house_id` | Legacy column — **no longer set directly**. A worker's house is derived exclusively from the crew they belong to (`crew.house_id`). V31 migration nulled all direct assignments. |

**API:** `GET/POST /api/workers`, `PUT/DELETE /api/workers/{id}`

The worker DTO includes resolved fields: `crewName`, `crewHouseId/crewHouseName` (house the crew works on), `managerId/managerName`, `leaderId/leaderName`, `houseId/houseName` (same as `crewHouseId/crewHouseName`).

Worker cards on the dashboard show: Role, Trade, Crew, Working On (crew's house), Manager, Crew Leader.

### Crews

Crews are a first-class entity (`crew` table, `V12`).

| Field        | Notes |
|--------------|-------|
| `name`       | Required |
| `manager_id` | FK to the CREW_MANAGER responsible for this crew |
| `house_id`   | FK to the House the crew is currently working on (added by `V15`) |

**API:** `GET/POST /api/crews`, `PUT/DELETE /api/crews/{id}`, `GET /api/crews/org-chart`

Crew cards on the dashboard show: Manager, Leader, assigned House, and a collapsible Members list.

Business rules:
- A worker can be CREW_LEADER of only one crew at a time; the frontend warns and auto-removes from the old crew when reassigning.
- Each crew has at least 5 CREW_MEMBERs plus one CREW_LEADER (seeded by `V14` and `V17`).

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

Deleting a scaffold automatically resets the associated house's `scaffold_status` back to `NONE`.

## Dashboard — five-mode toggle

The dashboard has a toggle at the top: **Houses | Workers | Crews | Scaffold | Travel Pay**.

| Mode | Content |
|------|---------|
| Houses | House cards with materials (name + quantity), phase chip, scaffold status chip |
| Workers | Worker cards showing Role, Trade, Crew, Working On, Manager, Crew Leader; Add/Edit/Delete; search bar (filters by all attributes) |
| Crews | Crew cards showing Manager, Leader, assigned House, collapsible Members list; org-chart view; Add/Edit/Delete |
| Scaffold | Scaffold cards with status, assigned house, dates; Add/Edit/Delete; search bar |
| Travel Pay | Table of all workers with their road route distance and daily fuel cost; see below |

### Travel Pay dashboard tab

Evaluates every worker's commute cost against a configurable center and radius.

**Controls (all update the table and totals live):**
- **City Center** — dropdown of 28 Bulgarian cities (default: Sofia)
- **Radius** — slider 5–300 km (default: 25 km); workers whose house falls inside pay nothing
- **Fuel €/L** — adjustable fuel price (default: €1.80); consumption fixed at 7 L/100 km
- **Search** — filters table by worker name, crew, or house name in real time

**Table columns:** Worker · Crew · House · Route (km) · Daily Cost (€) · Status badge

**Status badges:**
- `💸 Pay needed` — house is outside the radius; road route and cost are shown
- `✅ Within radius` — no travel pay required
- `No coords` — house has no GPS coordinates on file

**Summary line** and **total cost bar** both update whenever the search or row selection changes, reflecting only visible rows.

**Click a row** to isolate that worker (click again to deselect). Hover highlights rows in blue; selected row highlighted in purple.

**Live totals** — a summary line and a purple total bar below the controls both recalculate instantly as you search or select rows, reflecting only the currently visible workers.

**Export PDF** — downloads a formatted landscape report with city/radius/fuel settings in the header, the filtered table, and the total daily fuel cost at the bottom. Respects current search and row selection.

**i18n** — all strings (tab button, labels, headers, badges, totals, PDF content) are fully translated in both English and Bulgarian.

**Color theme** — purple (`#a78bfa`) throughout: tab button, slider, export button, cost values, pay badge, row highlight, and total bar.

## Map View — nine-mode toggle

| Mode | Description |
|------|-------------|
| 🏠 Houses | Standard house markers with materials popup |
| 🏗️ Scaffold | Markers coloured by scaffold status; popup shows scaffold info; legend at bottom |
| 👷 Workers | Worker emoji markers (👔/🦺/👷 by role) with crew, manager, leader, trade popup; filterable by manager |
| 🏠👷 Houses & Workers | Both layers — house markers + worker markers |
| 👥 Crews | Select a crew from the dropdown; all members with coordinates appear as markers |
| 🏠👥 House & Crew | Select a house; its assigned crew auto-loads and all members appear on the map. A status label shows the crew name (clickable to re-render) or indicates no crew is assigned. The crew dropdown can also be set independently. |
| 💰 Travel Pay | Select a city center (28 Bulgarian cities, default Sofia) and a radius slider (5–300 km). Pick a worker to see their assigned house on the map with a road route drawn via OSRM. Shows whether travel pay is needed (house outside the radius) with round-trip fuel cost calculation (adjustable €/L price). Optional car animation: 🚗 drives the route with worker name label, 🤚💰 hand collects the half-trip cost at each end. |
| 🏬 Warehouses | Company depot markers with stock popup (materials + quantities). |
| 🏭 Suppliers | External supplier markers with stock popup (materials + quantities). |

### Travel Pay mode details

- **City center selector**: 28 Bulgarian cities (Sofia, Plovdiv, Varna, Burgas, etc.) with a green dot marking the center on the map.
- **Radius circle**: red transparent circle; the slider adjusts 5–300 km live.
- **Route**: actual road route fetched from the OSRM public API drawn in bright blue. Falls back to a dashed straight line if OSRM is unreachable.
- **Cost**: round trip km × 7L/100km × fuel price (€/L, user-adjustable). Shown only when the house is outside the radius.
- **Animation** (▶ Play): car emoji drives the road route once; 🤚💰 hand with the half-trip cost (€) appears at each endpoint; button resets after one loop.

## Attendance (Check-in / Check-out)

Workers can check in and out of their assigned house via a QR-code link. The page requires no login.

### Check-in flow

1. A unique `checkin_token` (UUID) is generated per house and stored in `V24`.
2. Each house QR-code links to `/checkin/{token}`.
3. The worker selects their name from a searchable dropdown (only crew members for that house are shown).
4. GPS location is required — checked to be within 200 m of the house coordinates.
5. One session per worker per day; the same device must be used to check out.

### Admin attendance views

| Endpoint | Description |
|----------|-------------|
| `GET /api/attendance/crew/{crewId}?date=YYYY-MM-DD` | All sessions for a crew on a given date |
| `GET /api/attendance/worker/{workerId}?from=…&to=…` | All sessions for a worker in a date range |

Both endpoints are `@Transactional(readOnly=true)` and use `JOIN FETCH` to avoid lazy-init exceptions.

### Timezone

The work day boundary uses the configured app timezone (default `Europe/Sofia`). Override via `APP_TIMEZONE` env var.

### Attendance dashboard tab

The dashboard has an **Attendance** tab with:
- **By Crew** — pick a crew + date, see all check-in/out times and durations for that day.
- **By Worker** — pick a worker + date range, see a log of all sessions with durations.
- Both tabs have a **Export PDF** button (jsPDF + autoTable) to download a formatted report.
- Worker and crew selectors are searchable (autocomplete).

## Warehouses & Depots

Company-owned depots (`depot` table, `V27`) hold stock for the route optimizer's tier-2 fulfillment.

**API:** `GET /api/warehouses`, `POST/PUT/DELETE /api/warehouses/{id}`

Each depot has: `name`, `location`, `lat/lng`, and inventory lines (`depot_inventory`).

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

