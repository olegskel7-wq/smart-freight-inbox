<#
.SYNOPSIS
    Golden-set snapshot: run the demo mail scenarios through the live pipeline and record
    what each ticket ended up as, then diff two snapshots.

.DESCRIPTION
    The safety net for refactoring: a behaviour-preserving change must leave every golden
    scenario with the same outcome. Capture a snapshot before the change and after it, then
    compare. Needs the demo harness running (see the header of demo-mail.ps1).

    Capture sends every scenario with a run tag in its Message-ID, waits until no ticket of
    the run is still in the pipeline (RECEIVED/NEW/PROCESSING/RETRY_LATER), and writes
    golden-snapshots/<label>.json - one row per tenant-scenario: status, type, urgency,
    sentiment, identifier, document handling, mailbox, assignment, draft present, confidence,
    audit actions, and whether a shipment watch / quote follow-up was armed.

    Ollama runs with its default sampling, so classification and drafting are not fully
    deterministic. Capture the baseline twice and compare the two: whatever differs between
    them is natural LLM noise, not a regression.

    Capture sends every set by default (-Set all): the live-demo scenarios plus the "hard" LLM-eval
    cases from demo-mail.ps1. Score checks ONE snapshot against the hand-written expected outcomes in
    scripts/golden-expected.json (ROADMAP L0) and prints per-field accuracy plus every miss - the
    yardstick for comparing models or prompts, where Compare only says "something changed".

    Script execution is disabled by default on Windows - run it as:
      powershell -ExecutionPolicy Bypass -File scripts\golden-snapshot.ps1 [args]

.EXAMPLE
    .\scripts\golden-snapshot.ps1 -Capture -Label baseline-1
    .\scripts\golden-snapshot.ps1 -Compare golden-snapshots\baseline-1.json -Against golden-snapshots\baseline-2.json
    .\scripts\golden-snapshot.ps1 -Compare a.json -Against b.json -Ignore confidence,has_draft
    .\scripts\golden-snapshot.ps1 -Capture -Label qwen-1 -Set all
    .\scripts\golden-snapshot.ps1 -Score golden-snapshots\qwen-1.json
#>
[CmdletBinding(DefaultParameterSetName = 'Capture')]
param(
    [Parameter(ParameterSetName = 'Capture')]
    [switch]$Capture,
    [Parameter(ParameterSetName = 'Capture')]
    [string]$Label = '',
    [Parameter(ParameterSetName = 'Capture')]
    [int]$TimeoutMinutes = 15,
    [Parameter(ParameterSetName = 'Capture')]
    [ValidateSet('demo', 'hard', 'all')]
    [string]$Set = 'all',

    [Parameter(ParameterSetName = 'Compare', Mandatory = $true)]
    [string]$Compare,
    [Parameter(ParameterSetName = 'Compare', Mandatory = $true)]
    [string]$Against,
    [Parameter(ParameterSetName = 'Compare')]
    [string[]]$Ignore = @(),

    [Parameter(ParameterSetName = 'Score', Mandatory = $true)]
    [string]$Score,
    [Parameter(ParameterSetName = 'Score')]
    [string]$Expected = ''
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

# psql via stdin (no quoting games with native args in PowerShell 5.1). The live DB prints a
# collation-version WARNING on every connect; stderr is dropped, the exit code still counts.
function Invoke-Sql([string]$sql) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $repo
    try {
        $out = $sql | docker compose exec -T postgres psql -U postgres -d smartfreight -AtXq -v ON_ERROR_STOP=1 -f - 2>$null
        if ($LASTEXITCODE -ne 0) { throw "psql failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
        $ErrorActionPreference = $prev
    }
    (@($out) | Where-Object { $_ -is [string] }) -join "`n"
}

function Save-Snapshot([string]$runId, [string]$path) {
    $sql = @"
SELECT coalesce(json_agg(t ORDER BY t.key), '[]') FROM (
  SELECT substring(e.message_id FROM '^<demo-$runId-(.+)-[0-9a-f]{32}@') AS key,
         e.status, e.ticket_type AS type, e.urgency, e.sentiment_negative AS angry,
         e.extracted_identifier AS identifier, e.requested_doc_type AS doc_type,
         e.attached_document_id IS NOT NULL AS doc_attached,
         m.username AS mailbox, e.assignee_user_id IS NOT NULL AS assigned,
         coalesce(e.draft_reply, '') <> '' AS has_draft,
         e.sla_deadline_at IS NOT NULL AS has_sla, e.retry_count,
         round(e.ai_confidence::numeric, 2) AS confidence,
         (SELECT coalesce(string_agg(a.action, ',' ORDER BY a.action), '')
            FROM ticket_audit_log a WHERE a.ticket_id = e.id) AS audit,
         EXISTS (SELECT 1 FROM shipment_watch w WHERE w.tenant_id = e.tenant_id
                   AND w.identifier = e.extracted_identifier AND w.updated_at >= e.created_at) AS watch_armed,
         EXISTS (SELECT 1 FROM quote_follow_up q WHERE q.ticket_id = e.id) AS followup_armed
    FROM email_ticket e LEFT JOIN tenant_mailbox m ON m.id = e.mailbox_id
   WHERE e.message_id LIKE '<demo-$runId-%'
) t;
"@
    $json = Invoke-Sql $sql
    # PowerShell 5.1 emits a parsed JSON array as ONE object - ForEach-Object unrolls it into rows.
    $rows = @($json | ConvertFrom-Json | ForEach-Object { $_ })
    New-Item -ItemType Directory -Force (Split-Path -Parent $path) | Out-Null
    ConvertTo-Json -InputObject $rows -Depth 4 | Out-File -FilePath $path -Encoding utf8
    return $rows
}

if ($PSCmdlet.ParameterSetName -eq 'Capture') {
    $runId = 'g' + (Get-Date -Format 'yyyyMMddHHmmss')
    if (-not $Label) { $Label = $runId }
    $path = Join-Path $repo "golden-snapshots\$Label.json"
    # demo-mail.ps1 reports through Write-Host; 6>&1 captures that stream to read back how many it sent.
    $sendLog = & (Join-Path $PSScriptRoot 'demo-mail.ps1') -RunId $runId -Set $Set 6>&1
    $sendLog | ForEach-Object { Write-Host $_ }
    $expected = [int](($sendLog | Select-String 'Sent (\d+) email').Matches[0].Groups[1].Value)
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    do {
        Start-Sleep -Seconds 10
        $counts = (Invoke-Sql @"
SELECT count(*) || ' ' || count(*) FILTER (WHERE status IN ('RECEIVED','NEW','PROCESSING','RETRY_LATER'))
  FROM email_ticket WHERE message_id LIKE '<demo-$runId-%';
"@) -split ' '
        $total = [int]$counts[0]; $pending = [int]$counts[1]
        Write-Host ("[{0:HH:mm:ss}] tickets {1}/{2}, still in pipeline {3}" -f (Get-Date), $total, $expected, $pending)
    } while (($total -lt $expected -or $pending -gt 0) -and (Get-Date) -lt $deadline)
    # Auto-send leaves DRAFT_READY only after the pipeline TX commits - give it a moment.
    Start-Sleep -Seconds 15
    $rows = Save-Snapshot $runId $path
    if ($total -lt $expected -or $pending -gt 0) {
        Write-Warning "Timed out: $total of $expected ticket(s) ingested, $pending still in the pipeline - snapshot is partial."
    }
    Write-Host "`nSaved $($rows.Count) ticket(s) of run $runId to $path"
    $rows | Format-Table key, status, type, urgency, angry, identifier, audit -AutoSize
    return
}

# ---- Score: one snapshot against the hand-written expected outcomes (scripts/golden-expected.json) ----
if ($PSCmdlet.ParameterSetName -eq 'Score') {
    if (-not $Expected) { $Expected = Join-Path $PSScriptRoot 'golden-expected.json' }
    # email_ticket.urgency is the Urgency enum stored as its ORDINAL (CLAUDE.md decision 21).
    $urgencyNames = @('LOW', 'NORMAL', 'HIGH')
    $rows = @{}
    foreach ($r in @(Get-Content $Score -Raw | ConvertFrom-Json | ForEach-Object { $_ })) { $rows[$r.key] = $r }
    $spec = Get-Content $Expected -Raw -Encoding UTF8 | ConvertFrom-Json
    $fields = @('status', 'type', 'urgency', 'angry', 'identifier', 'doc_type', 'doc_attached', 'mailbox', 'assigned')
    $tally = [ordered]@{}
    foreach ($f in $fields) { $tally[$f] = [pscustomobject]@{ field = $f; correct = 0; total = 0 } }
    $misses = New-Object System.Collections.Generic.List[object]
    $absent = New-Object System.Collections.Generic.List[string]
    $perfect = 0; $scored = 0
    foreach ($case in $spec.scenarios) {
        $row = $rows[$case.key]
        # Not sent in this capture (e.g. -Set demo) or never ingested: listed, not scored.
        if (-not $row) { $absent.Add($case.key); continue }
        $scored++
        $caseOk = $true
        foreach ($f in $fields) {
            $want = $case.expect.PSObject.Properties[$f]
            if (-not $want) { continue }   # field not part of this scenario's expectation
            $tally[$f].total++
            $actual = "$($row.$f)"
            if ($f -eq 'urgency' -and $actual -match '^[0-2]$') { $actual = $urgencyNames[[int]$actual] }
            # A JSON array lists every acceptable value; null and "" both mean "empty".
            $allowed = @($want.Value | ForEach-Object { "$_" })
            if ($allowed -ccontains $actual) { $tally[$f].correct++; continue }
            $caseOk = $false
            $shown = @($allowed | ForEach-Object { if ($_ -eq '') { '(empty)' } else { $_ } }) -join ' | '
            $misses.Add([pscustomobject]@{ key = $case.key; field = $f; expected = $shown; actual = $(if ($actual -eq '') { '(empty)' } else { $actual }) })
        }
        if ($caseOk) { $perfect++ }
    }
    $unscored = @($rows.Keys | Where-Object { $k = $_; -not ($spec.scenarios | Where-Object { $_.key -eq $k }) })
    Write-Host "Scored $Score against $Expected`n"
    $tally.Values | Select-Object field, correct, total,
        @{ n = 'pct'; e = { if ($_.total) { [math]::Round(100 * $_.correct / $_.total) } else { '-' } } } | Format-Table -AutoSize
    Write-Host "$perfect of $scored scenario(s) fully correct."
    if ($misses.Count) { $misses | Format-Table key, field, expected, actual -AutoSize }
    if ($absent.Count) { Write-Warning "Not in this snapshot (not scored): $($absent -join ', ')" }
    if ($unscored.Count) { Write-Warning "No expectation for: $($unscored -join ', ')" }
    Write-Host 'Draft checklists (draft.must / draft.must_not) are not scored here: the snapshot holds no draft text.'
    return
}

# ---- Compare ----
$a = @(Get-Content $Compare -Raw | ConvertFrom-Json | ForEach-Object { $_ })
$b = @(Get-Content $Against -Raw | ConvertFrom-Json | ForEach-Object { $_ })
$ia = @{}; foreach ($r in $a) { $ia[$r.key] = $r }
$ib = @{}; foreach ($r in $b) { $ib[$r.key] = $r }
$diffs = New-Object System.Collections.Generic.List[object]
foreach ($key in (@($ia.Keys) + @($ib.Keys) | Sort-Object -Unique)) {
    if (-not $ia.ContainsKey($key)) { $diffs.Add([pscustomobject]@{ key = $key; field = '(ticket)'; before = '-'; after = 'present' }); continue }
    if (-not $ib.ContainsKey($key)) { $diffs.Add([pscustomobject]@{ key = $key; field = '(ticket)'; before = 'present'; after = '-' }); continue }
    foreach ($p in $ia[$key].PSObject.Properties) {
        if ($p.Name -eq 'key' -or $Ignore -contains $p.Name) { continue }
        $before = "$($p.Value)"; $after = "$($ib[$key].$($p.Name))"
        if ($before -ne $after) { $diffs.Add([pscustomobject]@{ key = $key; field = $p.Name; before = $before; after = $after }) }
    }
}
if ($diffs.Count -eq 0) {
    Write-Host "No differences across $($ia.Count) scenario(s)." -ForegroundColor Green
    exit 0
}
$diffs | Format-Table key, field, before, after -AutoSize
Write-Host "$($diffs.Count) difference(s)." -ForegroundColor Yellow
exit 1
