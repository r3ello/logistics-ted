<#
.SYNOPSIS
    Walks the CSV import API end to end against the sample files in this folder.

.DESCRIPTION
    Runs the eight-step sequence documented in README.md section 4: first sync, idempotence check,
    a sheet-side edit, an app-side edit, a conflict, a kept_app, and the steady state.

    Without -Apply every call uses mode=validate and nothing is written to the business tables
    (an import_batch row is still recorded for each run - that is by design).

    Windows PowerShell 5.1 compatible: uploads go through the bundled curl.exe because
    Invoke-RestMethod -Form requires PowerShell 6+. This file is deliberately pure ASCII - 5.1
    reads a BOM-less script as ANSI and would mangle anything else.

.PARAMETER Apply
    Use mode=apply. THIS WRITES to whatever DB_URL the running app points at.

.PARAMETER FromStep
    Resume at a given step (1-8). Use -FromStep 6 after running step 5's SQL by hand.

.EXAMPLE
    .\run-examples.ps1
    .\run-examples.ps1 -Apply
    .\run-examples.ps1 -Apply -FromStep 6
#>
[CmdletBinding()]
param(
    [string] $BaseUrl  = 'http://localhost:3002',
    [string] $User     = 'importer',
    [string] $Password = 'importer',
    [switch] $Apply,
    [ValidateRange(1, 8)] [int] $FromStep = 1
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if ($Apply) { $mode = 'apply' } else { $mode = 'validate' }

# ---- helpers ----------------------------------------------------------------

function Write-Step {
    param([int] $Number, [string] $Title, [string] $Expect)
    Write-Host ''
    Write-Host ("== Step {0} - {1}" -f $Number, $Title) -ForegroundColor Cyan
    if ($Expect) { Write-Host ("   expect: {0}" -f $Expect) -ForegroundColor DarkGray }
}

function Invoke-Import {
    param(
        [Parameter(Mandatory)] [string] $File,
        [string] $Entity  = 'materials',
        [string] $Mode    = $mode,
        [string] $OnError = 'skip',
        [string] $Delimiter,
        [string] $Decimal
    )
    $path = (Join-Path $here $File) -replace '\\', '/'
    if (-not (Test-Path $path)) { throw "Sample file not found: $path" }

    $url = "$BaseUrl/api/import/$Entity" + "?mode=$Mode" + "&onError=$OnError"
    if ($Delimiter) { $url += "&delimiter=$Delimiter" }
    if ($Decimal)   { $url += "&decimal=$Decimal" }

    Write-Host ("   POST {0}" -f $url) -ForegroundColor DarkGray
    Write-Host ("   file {0}" -f $File) -ForegroundColor DarkGray

    $raw = & curl.exe -s -X POST $url -H "Authorization: Bearer $token" -F "file=@$path;type=text/csv"
    if ($LASTEXITCODE -ne 0) { throw "curl.exe failed with exit code $LASTEXITCODE" }

    try { return $raw | ConvertFrom-Json }
    catch { throw "Unexpected response (not JSON): $raw" }
}

function Show-Report {
    param($Report)

    if ($Report.PSObject.Properties.Name -contains 'error') {
        # File-level failure: 400 with {error, code} and no report to show.
        $code = ''
        if ($Report.PSObject.Properties.Name -contains 'code') { $code = " [$($Report.code)]" }
        Write-Host ("   REJECTED{0}: {1}" -f $code, $Report.error) -ForegroundColor Red
        return
    }

    $colour = 'Green'
    if ($Report.status -eq 'partial') { $colour = 'Yellow' }
    if ($Report.status -eq 'failed')  { $colour = 'Red' }

    Write-Host ("   {0}/{1}  read={2} created={3} updated={4} unchanged={5} keptApp={6} conflicts={7} failed={8}" -f `
        $Report.status, $Report.mode, $Report.rowsRead, $Report.created, $Report.updated,
        $Report.unchanged, $Report.keptApp, $Report.conflicts, $Report.failed) -ForegroundColor $colour
    Write-Host ("   batchId {0}" -f $Report.batchId) -ForegroundColor DarkGray

    foreach ($w in $Report.warnings) {
        Write-Host ("   ! warn  line {0} [{1}] {2}: {3}" -f $w.line, $w.code, $w.column, $w.message) -ForegroundColor DarkYellow
    }
    foreach ($c in $Report.conflictDetails) {
        Write-Host ("   ! conflict #{0} line {1} key={2} column={3}  base={4} app={5} sheet={6}" -f `
            $c.conflictId, $c.line, $c.key, $c.column, $c.base, $c.app, $c.sheet) -ForegroundColor Magenta
    }
    foreach ($e in $Report.errors) {
        Write-Host ("   x error line {0} [{1}] {2}={3}: {4}" -f $e.line, $e.code, $e.column, $e.value, $e.message) -ForegroundColor Red
    }
}

# ---- 0. auth ----------------------------------------------------------------

Write-Host ("CSV import walkthrough against {0}  (mode={1})" -f $BaseUrl, $mode) -ForegroundColor White
if ($Apply) {
    Write-Host 'mode=apply - this writes to the database the app is connected to.' -ForegroundColor Yellow
}

$body = @{ username = $User; password = $Password } | ConvertTo-Json -Compress
try {
    $login = Invoke-RestMethod -Uri "$BaseUrl/api/login" -Method Post -ContentType 'application/json' -Body $body
} catch {
    throw "Login failed against $BaseUrl. Is the app running? ($($_.Exception.Message))"
}
$token = $login.token
Write-Host ("Logged in as {0} ({1})" -f $login.user.username, $login.user.role) -ForegroundColor DarkGray

if ($login.user.role -ne 'importer' -and $login.user.role -ne 'admin') {
    throw "The import API accepts the 'importer' and 'admin' roles; '$User' has role '$($login.user.role)'."
}

# ---- catalogue --------------------------------------------------------------

Write-Host ''
Write-Host '== GET /api/import/entities' -ForegroundColor Cyan
$entities = Invoke-RestMethod -Uri "$BaseUrl/api/import/entities" -Headers @{ Authorization = "Bearer $token" }
foreach ($e in $entities) {
    Write-Host ("   {0} (entityType={1}, key={2})" -f $e.name, $e.entityType, $e.keyColumn)
    Write-Host ("     required: {0}" -f ($e.required -join ', ')) -ForegroundColor DarkGray
    $cols = $e.columns.PSObject.Properties | ForEach-Object { "$($_.Name):$($_.Value)" }
    Write-Host ("     columns : {0}" -f ($cols -join ', ')) -ForegroundColor DarkGray
}

# ---- the walkthrough --------------------------------------------------------

if ($FromStep -le 1) {
    Write-Step 1 'dry run of the first sync (materials.csv)' 'created=3, nothing written'
    Show-Report (Invoke-Import -File 'materials.csv' -Mode 'validate')
}

if ($FromStep -le 2) {
    Write-Step 2 'apply the first sync' 'created=3'
    Show-Report (Invoke-Import -File 'materials.csv')
}

if ($FromStep -le 3) {
    Write-Step 3 'the same file again - idempotence' 'unchanged=3 (anything else means the baselines are wrong)'
    Show-Report (Invoke-Import -File 'materials.csv')
}

if ($FromStep -le 4) {
    Write-Step 4 'the sheet moves (materials-v2.csv)' 'created=1, updated=1, unchanged=2'
    Show-Report (Invoke-Import -File 'materials-v2.csv')
}

if ($FromStep -le 5) {
    Write-Step 5 'the app moves - run this SQL, then re-run with -FromStep 6' ''
    Write-Host ''
    Write-Host "     UPDATE material SET price = 27.50" -ForegroundColor White
    Write-Host "      WHERE id IN (SELECT entity_id FROM import_ref" -ForegroundColor White
    Write-Host "                    WHERE entity_type = 'material' AND external_key = 'MAT-001');" -ForegroundColor White
    Write-Host ''
    Write-Host '   Materials have no admin CRUD (MaterialController is GET-only), so the app-side edit' -ForegroundColor DarkGray
    Write-Host '   has to be made by hand. For houses or house stages this is just someone using the app.' -ForegroundColor DarkGray
    if (-not $Apply) {
        Write-Host '   (validate mode: steps 1-4 wrote nothing, so this SQL would find no rows.)' -ForegroundColor DarkGray
    }
    Write-Host ''
    Write-Host '   Then: .\run-examples.ps1 -Apply -FromStep 6' -ForegroundColor Cyan
    return
}

if ($FromStep -le 6) {
    Write-Step 6 'both sides moved (materials-conflict.csv)' 'status=partial, conflicts=1, MAT-001 untouched'
    Show-Report (Invoke-Import -File 'materials-conflict.csv')
}

if ($FromStep -le 7) {
    Write-Step 7 'the sheet is stale, the app wins (materials-v2.csv)' 'keptApp=1, unchanged=3'
    Show-Report (Invoke-Import -File 'materials-v2.csv')
}

if ($FromStep -le 8) {
    Write-Step 8 'once more - the two-baseline property' 'unchanged=4, price still 27.50'
    Show-Report (Invoke-Import -File 'materials-v2.csv')

    Write-Step 8 'bad input (materials-errors.csv)' 'failed=3 + 1 UNKNOWN_COLUMN warning, status=partial'
    Show-Report (Invoke-Import -File 'materials-errors.csv' -Mode 'validate')

    Write-Step 8 'Bulgarian dialect (materials-bg-semicolon.csv)' 'unchanged=4 - same meaning, different separators'
    Show-Report (Invoke-Import -File 'materials-bg-semicolon.csv' -Mode 'validate' -Delimiter 'semicolon' -Decimal 'comma')

    Write-Step 8 'same file, decimal=comma forgotten' 'status=failed, 4x INVALID_NUMBER'
    Show-Report (Invoke-Import -File 'materials-bg-semicolon.csv' -Mode 'validate' -Delimiter 'semicolon')

    Write-Step 8 'same file, delimiter=semicolon forgotten' '400 MISSING_COLUMN - header read as one column'
    Show-Report (Invoke-Import -File 'materials-bg-semicolon.csv' -Mode 'validate')

    Write-Step 8 'file-level failure (materials-missing-columns.csv)' '400 MISSING_COLUMN, no report'
    Show-Report (Invoke-Import -File 'materials-missing-columns.csv' -Mode 'validate')
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor Green
