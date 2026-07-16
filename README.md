# Tedhouse Logistics — Spring Boot

Spring Boot construction-site management platform. Tracks houses, crews, workers, attendance,
scaffold, document folders, and route optimization for material delivery. Single-page frontend
in vanilla JS with EN/BG i18n, backed by PostgreSQL.

## Stack

- Java 21
- Spring Boot 3.5 (pinned — see `CLAUDE.md` for why not 4.x)
- Spring Web MVC + Spring Data JPA + Spring Security (session auth)
- PostgreSQL with Flyway migrations (single consolidated baseline)
- Static frontend (HTML + vanilla JS + Leaflet)

## Prerequisites

- Java 21
- Maven (or use the bundled `./mvnw`)
- PostgreSQL 14+

## Quick start

1. **Point at a PostgreSQL instance** — set `DB_URL` / `DB_USER` / `DB_PASSWORD` env vars,
   or edit `src/main/resources/application.yaml`. Flyway runs a single `V1__baseline.sql`
   migration that creates the full schema and seeds all reference data on first start.

2. **Run the application**

   ```sh
   ./mvnw spring-boot:run
   ```

3. **Open** http://localhost:3002

## Default accounts

| Username | Role  |
|----------|-------|
| admin    | Admin |
| user     | User  |

## Configuration

Defaults live in `src/main/resources/application.yaml`. Override via env vars:

| Env var                    | Default                       | Notes                                                |
|----------------------------|-------------------------------|------------------------------------------------------|
| `DB_URL`                   | `jdbc:postgresql://...`       | Point at your own Postgres                           |
| `DB_USER`                  | `postgres`                    |                                                      |
| `DB_PASSWORD`              | _(empty)_                     |                                                      |
| `SERVER_PORT`              | `3002`                        |                                                      |
| `TEDHOUSE_JWT_SECRET`      | _(dev secret in yaml)_        | JWT signing key                                      |
| `ROUTING_PROVIDER`         | `google`                      | `google` for real road distances, `haversine` for fallback |
| `ROUTING_GOOGLE_API_KEY`   | _(committed dev key in yaml)_ | Required when `provider=google`; falls back to haversine if missing |
| `ROUTING_HAVERSINE_KMH`    | `40`                          | Average speed used to estimate minutes when Google is off |
| `ROUTING_BALANCED_ALPHA`   | `1.0`                         | Weight on km for the `balanced` objective            |
| `ROUTING_BALANCED_BETA`    | `1.0`                         | Weight on minutes for the `balanced` objective       |
| `APP_TIMEZONE`             | `Europe/Sofia`                | Timezone for attendance day boundaries               |

## Repository layout

```
src/main/java/com/bellgado/logistics_ted/
├── LogisticsTedApplication.java
├── config/             SecurityConfig, RoutingConfig, RoutingProperties
├── security/           Session-auth helpers (UserDetailsService, JSON 401/403 handlers)
├── domain/             JPA entities:
│                         House, HouseStage, Warehouse, Inventory, Material,
│                         Supplier, SupplierInventory, AppUser,
│                         Worker, WorkerRole, Crew, Scaffold, ScaffoldStatus,
│                         WorkSession, DocFolder, DocDocument,
│                         DocFolderTemplate, StageType
├── repository/         Spring Data JPA repositories (one per entity)
├── service/
│   ├── HouseService, InventoryService, ServerMessages
│   ├── RouteOptimizationService, SupplierFallbackService
│   ├── OrderHistoryService
│   ├── HouseTemplateFolderService  — seeds per-house doc folder structure
│   ├── distance/       RouteMatrixService, HaversineMatrixService,
│   │                   GoogleRoutesMatrixService, CachingRouteMatrixService
│   └── solver/         RouteSolver, HeuristicRouteSolver, ObjectiveSpec
└── web/
    ├── AuthController, HouseController, HouseStageController
    ├── InventoryController, MaterialController
    ├── OrderController, OrderHistoryController
    ├── WorkerController          — CRUD for workers (/api/workers)
    ├── CrewController            — CRUD for crews (/api/crews)
    ├── ScaffoldController        — CRUD for scaffold entities (/api/scaffolds)
    ├── ScaffoldTransportController — transport lookup (/api/scaffold-transport)
    ├── AttendanceController      — check-in/out sessions (/api/attendance)
    ├── DocFolderController       — company document folders (/api/doc-folders)
    ├── DocFolderTemplateController — house folder template admin (/api/folder-templates)
    ├── StageTypeController       — stage type admin (/api/stage-types)
    └── dto/                      OrderRequest, OrderResponse, HouseDto, …

src/main/resources/
├── application.yaml
├── application-prod.yaml
├── db/migration/
│   └── V1__baseline.sql          Full schema + seed data (all tables, FKs, indexes, sequences)
└── static/
    ├── index.html                SPA frontend (vanilla JS + Leaflet, EN/BG i18n)
    ├── checkin.html              Public check-in/out page (QR-code linked, no auth)
    └── map-picker.html           Standalone map pin picker (localStorage round-trip)
```

## Database

Single `V1__baseline.sql` migration creates all 29 tables with FK constraints, indexes,
CHECK constraints, and seeds all reference data. Never edit this file — add new `V{n}__*.sql`
migrations for schema changes.

Key tables: `house`, `house_stage`, `worker`, `crew`, `work_session`, `scaffold`,
`doc_folder`, `doc_document`, `doc_folder_template`, `stage_type`, `app_user`,
`warehouse`, `inventory`, `material`, `supplier`, `supplier_inventory`,
`depot`, `depot_inventory`, `customer_order`, `order_route_option`, `order_event`.

## Route optimization

`POST /api/calculate-order` runs a multi-stage pipeline:

1. **Candidate selection** — fetch houses that have any of the needed materials.
2. **Matrix build** — pairwise `RouteCostMatrix` (km + seconds). Provider is pluggable:
   - `provider=google` → real road distances via Google Routes API (falls back to haversine on failure).
   - `provider=haversine` → great-circle distance with minutes from `routing.haversine.speed-kmh`.
   - Both go through a process-local TTL cache.
3. **Heuristic solver** (`HeuristicRouteSolver`):
   - ≤15 relevant houses → exhaustive subset enumeration with NN + 2-opt.
   - >15 → greedy `quantity / cost` fallback.
4. **Material allocation** — closest house first per material; shortfalls become `deficit`.
5. **Supplier fallback** — deficit covered from `supplier_inventory`.
6. **Multi-objective alternatives** — pipeline runs three times: `shortest_distance`, `fastest_time`, `balanced(α·km + β·min)`.
7. **Pareto-relabel** — alternatives reassigned to whichever candidate route actually wins under each label.
8. **Dedup** — identical sequences collapse to a single entry.

## Workers & Crews

### Workers

| Field      | Notes |
|------------|-------|
| `name`     | Required |
| `role`     | `CREW_MANAGER`, `CREW_LEADER`, or `CREW_MEMBER` |
| `trade`    | Speciality (Roofing, Plumbing, Electricity, Framing, Finishing, …) — CREW_MANAGERs have no trade |
| `phone`    | Optional contact number |
| `email`    | Optional contact email |
| `location` | Auto-filled via OpenStreetMap reverse geocoding when a map pin is placed |
| `lat/lng`  | Validated: latitude −90..90, longitude −180..180 |
| `crew_id`  | FK to the crew the worker belongs to |
| `house_id` | Derived from `crew.house_id` — not set directly |

**API:** `GET/POST /api/workers`, `PUT/DELETE /api/workers/{id}`

### Crews

| Field        | Notes |
|--------------|-------|
| `name`       | Required |
| `leader_id`  | FK to the CREW_LEADER (required on create) |
| `manager_id` | FK to the CREW_MANAGER responsible for this crew |
| `house_id`   | FK to the House the crew is currently working on |
| `stage_order`| Which stage the crew is working on |
| `lat/lng`    | Optional crew location |

**API:** `GET/POST /api/crews`, `PUT/DELETE /api/crews/{id}`, `GET /api/crews/org-chart`

Business rules:
- A CREW_LEADER can only lead one crew; the Add Crew modal only shows unassigned leaders.
- Members already in another crew are not shown in the Add Crew member search.

## House Stages

Each house has 26 stages tracked in `house_stage`. Stage types are managed in the Admin panel.

**API:** `GET/PUT /api/houses/{id}/stages`, `GET /api/stage-types`

Stages flow: `NOT_STARTED` → `IN_PROGRESS` → `DONE`. Setting a stage to `IN_PROGRESS`
links the assigned crew's `house_id` to that house and records `start_date`.

## Scaffold Transport

Scaffolds are a first-class entity with `status` (`NONE` / `AVAILABLE` / `IN_USE`),
optional `start_date` / `end_date`, and an optional `house_id` FK.

**API:** `GET/POST /api/scaffolds`, `PUT/DELETE /api/scaffolds/{id}`

`GET /api/scaffold-transport?destinationHouseId={id}&startLat={lat}&startLng={lng}` finds
the closest available scaffold; when driver coordinates are supplied, the Google Maps URL
routes driver → scaffold pickup → destination.

## Dashboard — six-mode toggle

| Mode | Content |
|------|---------|
| Houses | House cards with materials, phase chip, scaffold status chip |
| Workers | Worker cards with role, trade, crew, house, manager, leader; Add/Edit/Delete; search |
| Crews | Crew cards with manager, leader, house, members list; org-chart view; Add/Edit/Delete |
| Scaffold | Scaffold cards with status, assigned house, dates; Add/Edit/Delete; search |
| Travel Pay | Worker commute costs vs configurable city center and radius; Export PDF |
| Attendance | By Crew (date) or By Worker (date range) check-in/out logs; Export PDF |

## Attendance (Check-in / Check-out)

Workers check in/out via QR-code link — no login required.

1. Each house has a unique `checkin_token` (UUID).
2. QR-code links to `/checkin/{token}`.
3. Worker selects their name (only that house's crew shown).
4. GPS required — must be within 200 m of house coordinates.
5. One session per worker per day.

**API:** `GET /api/attendance/crew/{crewId}?date=YYYY-MM-DD`, `GET /api/attendance/worker/{workerId}?from=…&to=…`

## Document Folders

Company-wide document folder tree managed in the Admin panel under **Company Documentation**.

- **Company Folders Template** — global folder structure; Add/Edit/Delete folders with drag-and-drop reorder.
- **House Folder Template** — subfolder structure seeded into each house's doc folder on creation; Admin CRUD.

**API:** `GET/POST /api/doc-folders`, `PUT/DELETE /api/doc-folders/{id}`, `GET /api/doc-folders/flat`  
**API:** `GET/POST /api/folder-templates`, `PUT/DELETE /api/folder-templates/{id}`

## Map View — nine-mode toggle

| Mode | Description |
|------|-------------|
| 🏠 Houses | House markers with materials popup |
| 🏗️ Scaffold | Markers coloured by scaffold status |
| 👷 Workers | Worker markers (👔/🦺/👷 by role) with crew/manager/leader popup; filterable by manager |
| 🏠👷 Houses & Workers | Both layers |
| 👥 Crews | Select a crew; all members with coordinates appear |
| 🏠👥 House & Crew | Select a house; its crew auto-loads on the map |
| 💰 Travel Pay | City center + radius; pick a worker to see their route, fuel cost, and optional car animation |
| 🏬 Warehouses | Depot markers with stock popup |
| 🏭 Suppliers | Supplier markers with stock popup |

## Admin Panel

Accessible from the sidebar under **Admin**. Requires admin role.

- **Company Folders Template** — manage the global doc folder tree; drag-and-drop to reorder.
- **House Folder Template** — manage the subfolder template seeded into each new house folder.
- **Stage Types** — manage the 26 construction stage types (name EN/BG, order).

## Internationalisation

All UI strings are in the `i18n` object (`en` / `bg`). The language toggle re-renders labels,
modal options, map buttons, dashboard chips, and popup rows without losing entered values.

## Tests

Algorithm goldens and unit tests run without a database:

```sh
./mvnw -Dtest=RouteOptimizationServiceTest,RouteOptimizationServiceTwoOptTest,RouteCostCacheTest,CachingRouteMatrixServiceTest,GoogleRoutesMatrixServiceTest,SupplierFallbackServiceTest test
```
