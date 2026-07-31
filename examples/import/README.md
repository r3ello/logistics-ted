# CSV import API — call examples

> **Looking for the contract to hand to an external client?** That is the OpenAPI document:
> `src/main/resources/openapi/import-openapi.yaml`, served to `importer`/`admin` only from
> `GET /api/import/openapi.yaml` (and `.json`), with a viewer at
> [`/import-docs.html`](http://localhost:3002/import-docs.html).
> This file is the internal companion to it — runnable examples with real responses.


Ready-to-send requests for the endpoints added on `migration-phase-1`
(`ImportController`, `/api/import/**`). Every response body below is what the current code actually
produces for the CSV files sitting next to this README — not sketches.

Design rationale lives in `DATA_IMPORT_PLAN.md`; this file is only *how to call it*.

- Base URL: `http://localhost:3002` (`server.port`, override with `SERVER_PORT`)
- **Role `importer` or `admin`** (`@PreAuthorize("hasAnyRole('ADMIN','IMPORTER')")` plus an explicit
  `SecurityConfig` matcher), JWT bearer on every call. An `importer` token reaches `/api/import/**`
  and is rejected with `403` everywhere else — see "The importer role" below.
- Only entity implemented today: **`materials`**
- Upload field name: **`file`**, `multipart/form-data`, max 10 MB / 50 000 rows

> `mode=validate` is the default, so a call without `?mode=` never writes business data.
> `mode=apply` writes to whatever `DB_URL` points at — **including the live server**. Check it first.

---

## The importer role

The import API is the only part of the application an `importer` account can reach. It is created by
hand — there is no user-management API — after Flyway `V4__importer_role.sql` has widened the
`app_user.role` CHECK:

```sql
INSERT INTO public.app_user (username, password_hash, role)
VALUES ('importer', '$2b$10$...', 'importer');   -- bcrypt, cost 10
```

Enforced in two places, both of which must list the role:

- `SecurityConfig.java` — `.requestMatchers("/api/import/**").hasAnyRole("ADMIN", "IMPORTER")`,
  which **must stay above** the generic `.requestMatchers("/api/**").hasAnyRole("ADMIN","USER")`.
- `ImportController` / `ImportDocsController` — `@PreAuthorize("hasAnyRole('ADMIN','IMPORTER')")`.

Everything else under `/api/**` still requires ADMIN or USER, so an importer token is rejected by the
filter chain rather than merely hidden in the UI. In the dashboard, `initApp()` redirects an
`importer` login to `/import-docs.html` instead of rendering the admin views.

## 0. Get a token

```sh
curl -s -X POST http://localhost:3002/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"importer","password":"..."}'
```

```json
{ "token": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 28800,
  "user": { "id": 3, "username": "importer", "role": "importer" } }
```

Windows PowerShell 5.1:

```powershell
$login = Invoke-RestMethod -Uri http://localhost:3002/api/login -Method Post `
  -ContentType 'application/json' -Body '{"username":"importer","password":"..."}'
$t = $login.token
```

Everything below assumes `TOKEN` / `$t` holds that value.

---

## 1. `GET /api/import/entities` — what can be imported

Self-describing catalogue, derived from the `EntityImporter` beans, in dependency order. Use it to
drive a UI or to check which columns a file may carry.

```sh
curl -s http://localhost:3002/api/import/entities -H "Authorization: Bearer $TOKEN"
```

```json
[
  {
    "name": "materials",
    "entityType": "material",
    "keyColumn": "key",
    "required": [ "name", "unit" ],
    "columns": { "name": "text", "price": "decimal", "unit": "text" },
    "dependsOn": []
  }
]
```

`columns` is the full set the sync **owns** for that entity — a field not listed can never be written
by an import and can never conflict. `required` must be present in the header *and* non-blank in
every row.

---

## 2. `GET /api/import/templates/{entity}` — blank CSV to hand to the client

`text/csv`, header only, UTF-8 **with BOM** so Excel opens the Cyrillic correctly.

```sh
curl -s -OJ http://localhost:3002/api/import/templates/materials -H "Authorization: Bearer $TOKEN"
# → materials.csv containing:  key,name,price,unit
```

```powershell
Invoke-RestMethod -Uri http://localhost:3002/api/import/templates/materials `
  -Headers @{ Authorization = "Bearer $t" } -OutFile .\materials-template.csv
```

Unknown entity → `404` with an empty body.

---

## 3. `POST /api/import/{entity}` — run one file

```
POST /api/import/materials?mode=validate&onError=skip&delimiter=comma&decimal=dot
Content-Type: multipart/form-data;  field name: file
```

| Param | Values | Default | Meaning |
|---|---|---|---|
| `mode` | `validate` \| `apply` | `validate` | `validate` parses, resolves keys and runs the full merge, then reports **without a single write**. Same report shape either way. |
| `onError` | `skip` \| `abort` | `skip` | `skip` commits the good rows. `abort` rolls the file back if any row failed — the `import_batch` row still survives, so you can read why. |
| `delimiter` | `comma` \| `semicolon` \| `tab` | `comma` | |
| `decimal` | `dot` \| `comma` | `dot` | Never auto-detected: `1,234` is genuinely ambiguous. A comma-decimal file must be `;`-delimited. |

`createMissing` is documented in the plan but **not implemented** — unknown keys are always created.

### The report

`200 OK` whenever the *file* parsed, even with failed rows; the verdict is in `status`
(`ok` \| `partial` \| `failed`). Counters are per row, and every error carries a line and a column.

---

## 4. A full run, end to end

Seven calls against the four CSV files here. Each step's numbers are what you should actually see —
if they differ, the merge state is not what this walkthrough assumes (start from a clean
`import_ref` / `import_batch`).

`examples/import/run-examples.ps1` executes exactly this sequence.

### Step 1 — dry run of the first sync

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=validate" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials.csv;type=text/csv"
```

```json
{
  "batchId": "0f6a1c8e-3b52-4a1d-9f2c-7d41e0a9b511",
  "entity": "materials", "mode": "validate", "status": "ok",
  "rowsRead": 3, "created": 3, "updated": 0, "unchanged": 0,
  "keptApp": 0, "conflicts": 0, "failed": 0,
  "warnings": [], "conflictDetails": [], "errors": []
}
```

Nothing was written to `material`. One `import_batch` row **was** written — a validate run is still a
run, and the history records it.

### Step 2 — apply it

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=apply" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials.csv;type=text/csv"
```

Same counters (`created: 3`), and now three `material` rows plus three `import_ref` mappings exist.

### Step 3 — the same file again: the idempotence check

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=apply" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials.csv;type=text/csv"
```

```json
{ "entity": "materials", "mode": "apply", "status": "ok",
  "rowsRead": 3, "created": 0, "updated": 0, "unchanged": 3,
  "keptApp": 0, "conflicts": 0, "failed": 0, ... }
```

**`unchanged: 3` is the property that matters.** A steady-state re-run that reports anything else
means the baselines are not being re-based correctly and every future run would rewrite rows it
shouldn't. Note `24.50` in the file and `24.50` in a `numeric(10,2)` column both normalise to `24.5`,
so representation differences don't register as changes.

### Step 4 — the sheet moves: one price edited, one material added

`materials-v2.csv` raises MAT-001 to `26.90` and adds MAT-004.

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=apply" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials-v2.csv;type=text/csv"
```

```json
{ "rowsRead": 4, "created": 1, "updated": 1, "unchanged": 2,
  "keptApp": 0, "conflicts": 0, "failed": 0, "status": "ok", ... }
```

### Step 5 — the app moves (simulated)

Materials have no admin CRUD yet (`MaterialController` is GET-only, plan §6.6), so an app-side edit
has to be made by hand. For houses or house stages this is just someone using the app.

```sql
-- go through import_ref rather than matching on the Cyrillic name: keys are ASCII and stable,
-- and material.name is not unique
UPDATE material SET price = 27.50
 WHERE id IN (SELECT entity_id FROM import_ref
               WHERE entity_type = 'material' AND external_key = 'MAT-001');
```

### Step 6 — both sides moved → **conflict**

`materials-conflict.csv` sends `29.90` for MAT-001 while the DB now holds `27.50`.

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=apply" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials-conflict.csv;type=text/csv"
```

```json
{
  "entity": "materials", "mode": "apply", "status": "partial",
  "rowsRead": 4, "created": 0, "updated": 0, "unchanged": 3,
  "keptApp": 0, "conflicts": 1, "failed": 0,
  "warnings": [],
  "conflictDetails": [
    { "conflictId": 1, "line": 2, "key": "MAT-001", "column": "price",
      "base": "26.9", "app": "27.5", "sheet": "29.9",
      "message": "Changed in both the app and the sheet since the last sync. Row left untouched." }
  ],
  "errors": []
}
```

The MAT-001 row is **not written** — not even its unrelated columns, because a half-applied row is
worse than none. The other three rows still report `unchanged`. `conflictId` is the `import_conflict`
row id; it is `null` in `mode=validate`, which persists nothing.

> **Known gap:** `GET /api/import/conflicts` and `POST /api/import/conflicts/{id}/resolve` are not
> built yet (plan §W, next item). Until they are, a conflict is closed by editing one of the two
> sides so they agree — which is what step 7 does.

### Step 7 — the sheet is stale, the app wins → **kept_app**

Re-send `materials-v2.csv` (still `26.90`) with the DB at `27.50`:

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=apply" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials-v2.csv;type=text/csv"
```

```json
{ "rowsRead": 4, "created": 0, "updated": 0, "unchanged": 3,
  "keptApp": 1, "conflicts": 0, "failed": 0, "status": "ok", ... }
```

The sheet did not move since the last run, so it is stale and the app value survives.

### Step 8 — run it once more: the two-baseline property

Same file, unchanged, again:

```json
{ "rowsRead": 4, "created": 0, "updated": 0, "unchanged": 4,
  "keptApp": 0, "conflicts": 0, "failed": 0, "status": "ok", ... }
```

`unchanged: 4`, and `material.price` is still `27.50`. This is the whole reason `import_ref` keeps
**two** snapshots: with a single baseline the untouched-but-stale `26.90` in the sheet would read as
a fresh edit here and silently revert the app value one run later.

---

## 5. Bad input

### Row-level errors — `materials-errors.csv`

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=validate" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials-errors.csv;type=text/csv"
```

```json
{
  "entity": "materials", "mode": "validate", "status": "partial",
  "rowsRead": 4, "created": 1, "updated": 0, "unchanged": 0,
  "keptApp": 0, "conflicts": 0, "failed": 3,
  "warnings": [
    { "line": 0, "code": "UNKNOWN_COLUMN", "column": "коментар",
      "message": "Column is not managed by the 'materials' importer and was ignored." }
  ],
  "conflictDetails": [],
  "errors": [
    { "line": 3, "column": "key", "value": "MAT-010", "code": "DUPLICATE_KEY",
      "message": "Key 'MAT-010' appears more than once in this file." },
    { "line": 4, "column": "name", "value": null, "code": "EMPTY_REQUIRED",
      "message": "Column 'name' is required." },
    { "line": 5, "column": "price", "value": "12,34", "code": "INVALID_NUMBER",
      "message": "Contains ',', but this file is being read with '.' as the decimal separator. Re-send with decimal=comma if the sheet uses ',' for decimals." }
  ]
}
```

`line` is the physical line in the file, so it matches what the spreadsheet maintainer sees.
Line 2 was fine and counts as `created`; with `mode=apply&onError=skip` it would be committed and the
other three reported. Add `&onError=abort` to roll the whole file back instead — the report is
identical, but nothing is written and the `import_batch` row records the failure.

### File-level failures — `400`, no report

A file that cannot be parsed at all has no per-row outcomes to report, only a reason:

```sh
curl -s -X POST "http://localhost:3002/api/import/materials" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials-missing-columns.csv;type=text/csv"
```

```json
{ "error": "Missing required column(s): name, unit. Found: key, price.", "code": "MISSING_COLUMN" }
```

> That also means **a price-only refresh is not possible today** for materials: `name` and `unit` are
> required columns, so the file must carry them even when it isn't changing them. (Absent-column
> handling itself works — a file with `key,name,unit` and no `price` leaves prices alone.)

Other file-level codes, same `{error, code}` shape:

| Trigger | `code` |
|---|---|
| File not UTF-8 (e.g. saved as Windows-1251) | `ENCODING` |
| Empty file / header row with no usable names | `NO_HEADER` |
| Same column name twice in the header | `DUPLICATE_COLUMN` |
| More than 50 000 data rows | `ROW_LIMIT_EXCEEDED` |
| `?delimiter=pipe` or anything unsupported | `MALFORMED_CSV` |

And without a `code`:

```jsonc
// unknown entity
{ "error": "Unknown import entity 'houses'. Known: [materials]." }
// no file part, or an empty file
{ "error": "No file uploaded." }
// mode=dryrun
{ "error": "mode must be 'validate' or 'apply'." }
```

`401` `{"error":"Unauthorized"}` without a token, `403` `{"error":"Forbidden"}` for any role other
than `importer` / `admin`, `415` if the request isn't `multipart/form-data`.

> A file over the 10 MB multipart limit currently comes back as **`500`**, not `413`:
> `MaxUploadSizeExceededException` isn't a Spring `ErrorResponse`, so it falls through to the
> catch-all in `GlobalExceptionHandler.java:81`. A one-line `@ExceptionHandler` would fix it.

---

## 6. Bulgarian-locale export

Sheets/Excel in a `bg-BG` locale writes `;` separators and `,` decimals. Same data as
`materials-v2.csv`, different dialect:

```sh
curl -s -X POST "http://localhost:3002/api/import/materials?mode=validate&delimiter=semicolon&decimal=comma" \
  -H "Authorization: Bearer $TOKEN" -F "file=@materials-bg-semicolon.csv;type=text/csv"
```

Run after step 8 it reports `unchanged: 4` — the dialect changes parsing, never meaning.

Get the dialect wrong and it is loud, never silently coerced — and the two mistakes fail at
different stages:

**Forgot `decimal=comma`** (`?delimiter=semicolon` alone) — the file parses, then every price is
rejected:

```json
{ "entity": "materials", "mode": "validate", "status": "failed",
  "rowsRead": 4, "created": 0, "failed": 4,
  "errors": [
    { "line": 2, "column": "price", "value": "26,90", "code": "INVALID_NUMBER",
      "message": "Contains ',', but this file is being read with '.' as the decimal separator. Re-send with decimal=comma if the sheet uses ',' for decimals." }
  ]
}
```

**Forgot `delimiter=semicolon`** — the header is read as one column named `key;name;unit;price`, so
the file is rejected before any row is touched:

```json
{ "error": "Missing required column(s): key, name, unit. Found: key;name;unit;price.",
  "code": "MISSING_COLUMN" }
```

---

## 7. Windows PowerShell 5.1

`Invoke-RestMethod -Form` needs PowerShell 6+, so use the bundled `curl.exe` for uploads:

```powershell
$t = (Invoke-RestMethod -Uri http://localhost:3002/api/login -Method Post `
        -ContentType 'application/json' -Body '{"username":"importer","password":"..."}').token

# catalogue
Invoke-RestMethod -Uri http://localhost:3002/api/import/entities -Headers @{ Authorization = "Bearer $t" }

# one file
$r = & curl.exe -s -X POST "http://localhost:3002/api/import/materials?mode=validate" `
        -H "Authorization: Bearer $t" -F "file=@materials.csv;type=text/csv" | ConvertFrom-Json
$r | Format-List entity, mode, status, rowsRead, created, updated, unchanged, keptApp, conflicts, failed
$r.errors | Format-Table line, column, code, message -AutoSize
```

Or run the whole §4 walkthrough:

```powershell
cd examples\import
.\run-examples.ps1                 # validate-only, writes nothing
.\run-examples.ps1 -Apply          # the real sequence — writes to $env:DB_URL
```

---

## 8. Not built yet

`ImportController` exposes three endpoints. These appear in `DATA_IMPORT_PLAN.md` §5 but **do not
exist**, so calls to them 404:

| Endpoint | Status |
|---|---|
| `GET /api/import/conflicts`, `POST /api/import/conflicts/{id}/resolve` | next up (plan §W.1) — conflicts are detected and persisted, but nothing can close one |
| `GET /api/import/batches`, `/batches/{publicId}` | run history is written, readable only via SQL (plan §W.2) |
| `POST /api/import/batch` (multi-file / zip, advisory lock) | phase 7 |
| `POST /api/import/batches/{publicId}/revert` | phase 7 |

Entities other than `materials` — houses, workers, crews, inventories, house stages, orders,
deliveries — are phases 4b and 6. `GET /api/import/entities` is the authority on what exists: it is
generated from the beans, so it cannot claim an importer that isn't there.
