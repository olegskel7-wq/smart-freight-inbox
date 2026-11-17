<#
.SYNOPSIS
    Demo mail harness traffic generator (Phase 4.8 S2).

.DESCRIPTION
    Drops realistic UK freight/haulage customer emails into the demo tenants' GreenMail
    inboxes over SMTP. The backend's IMAP IDLE adapters pick them up and run the REAL
    pipeline: ingestion -> classification -> extraction -> draft -> (auto-)reply. Replies
    land in Mailpit: http://localhost:8025.
    Before sending it re-extends the demo rate sheet when its rates have expired, so RFQ
    drafts keep quoting a price on a long-running stack (needs docker compose; warns and carries
    on without it).

    HOW TO RUN THE HARNESS (the one place this is documented):
    - Compose: put these two lines in .env (MAILBOX_ENCRYPTION_KEY is a file in SECRETS_DIR), so every
      routine `docker compose up -d` keeps the demo on - then run `docker compose up -d`:
        COMPOSE_PROFILES=demo
        DEMO_MAIL_ENABLED=true
    - IDE run config instead: DEMO_MAIL_ENABLED=true, DEMO_IMAP_HOST=localhost,
      DEMO_SMTP_HOST=localhost and MAILBOX_ENCRYPTION_KEY (copy from SECRETS_DIR).
    - Road tenant 90001 needs dev-seed V36 (job-ref extraction) - load it via psql.
    - Turn it OFF before any real pilot mail (ROADMAP Gate B14): delete both .env lines,
      `docker compose up -d` (backend removes the demo mailboxes), `docker compose stop greenmail mailpit`.

    Script execution is disabled by default on Windows - run it as:
      powershell -ExecutionPolicy Bypass -File scripts\demo-mail.ps1 [args]

.EXAMPLE
    .\scripts\demo-mail.ps1 -List
    .\scripts\demo-mail.ps1                                # every scenario, both tenants
    .\scripts\demo-mail.ps1 -Tenant midlands -Scenario rfq*
    .\scripts\demo-mail.ps1 -Repeat 5 -DelaySeconds 20     # slow drip for a live demo
    .\scripts\demo-mail.ps1 -Set hard                     # only the hard LLM-eval cases (golden-snapshot sends -Set all)
#>
param(
    [ValidateSet('all', 'felixstowe', 'midlands')]
    [string]$Tenant = 'all',
    [string]$Scenario = '*',
    # demo = the live-demo traffic (default); hard = messy real-inbox cases for LLM eval; all = both.
    [ValidateSet('demo', 'hard', 'all')]
    [string]$Set = 'demo',
    [int]$Repeat = 1,
    [int]$DelaySeconds = 2,
    [string]$SmtpHost = 'localhost',
    [int]$SmtpPort = 3025,
    # Tags each Message-ID as <demo-RUNID-tenant-scenario-guid@...> so golden-snapshot.ps1 can find this run's tickets.
    [ValidatePattern('^[a-z0-9]*$')]
    [string]$RunId = '',
    [switch]$List
)

$ErrorActionPreference = 'Stop'

# Mirrors application.yaml demo.mail.inboxes (tenant 1 / 90001) - keep them in sync.
# Keyed by tenant (its default inbox); tenant 1 also watches a second inbox, felixstowe-quotes.
# A scenario with an Inbox key is sent there, and its reply in Mailpit must come FROM that
# address (a reply leaves through the inbox the enquiry arrived on).
$inboxes = @{
    felixstowe          = 'ops@felixstowe-freight.example.com'
    'felixstowe-quotes' = 'quotes@felixstowe-freight.example.com'
    midlands            = 'dispatch@midlands-haulage.example.com'
}
function Get-Inbox($s) { $inboxes[$(if ($s.Inbox) { $s.Inbox } else { $s.Tenant })] }
function Get-Set($s) { if ($s.Set) { $s.Set } else { 'demo' } }

# Container / job refs match the port-service seed (V2/V3 sea containers, V7 road jobs),
# so status enquiries resolve to real tracking data.
$scenarios = @(
    # ---------------- Felixstowe Freight Solutions (sea freight, tenant 1) ----------------
    @{ Tenant = 'felixstowe'; Name = 'rfq-fcl'; Type = 'RFQ'
       From = 'James Whitaker <james.whitaker@northgate-imports.example.com>'
       Subject = 'Quote request: 3 x 40HC Ningbo to Felixstowe, early November'
       Body = "Hello,`r`n`r`nCould you quote for 3 x 40HC, FCL, Ningbo to Felixstowe, cargo ready 4 November? Commodity is flat-pack furniture, approx 18 tonnes per box. We would need delivery to our warehouse in Chelmsford (CM2).`r`n`r`nPlease include THC and customs clearance.`r`n`r`nKind regards,`r`nJames Whitaker`r`nNorthgate Imports Ltd" }
    @{ Tenant = 'felixstowe'; Name = 'rfq-quotes-desk'; Type = 'RFQ (2nd inbox)'; Inbox = 'felixstowe-quotes'
       From = 'Helen Brooks <helen.brooks@brookfield-trading.example.com>'
       Subject = 'LCL rate Shanghai to Felixstowe, 6 CBM'
       Body = "Hello quotes team,`r`n`r`nPlease quote LCL Shanghai to Felixstowe for 6 CBM / 2,100 kg of ceramic tiles, cargo ready 20 October, delivered to our unit in Colchester (CO4).`r`n`r`nMany thanks,`r`nHelen Brooks`r`nBrookfield Trading Ltd" }
    @{ Tenant = 'felixstowe'; Name = 'status-container'; Type = 'STATUS_UPDATE'
       From = 'Maria Kowalski <maria.kowalski@globalship.example.com>'
       Subject = 'Where is MSCU1234567?'
       Body = "Hi team,`r`n`r`nCan you give me an update on container MSCU1234567? Our customer is asking for an ETA at Felixstowe.`r`n`r`nThanks,`r`nMaria" }
    @{ Tenant = 'felixstowe'; Name = 'status-customs'; Type = 'STATUS_UPDATE'
       From = 'Tom Fisher <tom.fisher@harbourline.example.com>'
       Subject = 'CMAU5551234 still held?'
       Body = "Morning,`r`n`r`nIs CMAU5551234 still held at customs? If so, what documents are missing so we can chase the shipper today?`r`n`r`nRegards,`r`nTom Fisher" }
    @{ Tenant = 'felixstowe'; Name = 'booking'; Type = 'BOOKING_CONFIRM'
       From = 'Rachel Green <rachel.green@globaltrade.example.com>'
       Subject = 'Booking: 1 x 20GP Felixstowe to Rotterdam, sailing 12 Oct'
       Body = "Hello,`r`n`r`nPlease book 1 x 20GP Felixstowe to Rotterdam on the 12 October sailing at the rate you quoted last week. Shipper details and VGM to follow.`r`n`r`nPlease confirm the booking reference.`r`n`r`nBest,`r`nRachel" }
    @{ Tenant = 'felixstowe'; Name = 'invoice'; Type = 'INVOICE_QUERY'
       From = 'Accounts <accounts@acmeparts.example.com>'
       Subject = 'Invoice INV-20931 - demurrage charge query'
       Body = "Dear Sir/Madam,`r`n`r`nInvoice INV-20931 includes GBP 640 demurrage on HLXU8764321. Our records show the container was collected within the free time. Could you please review and send a corrected invoice?`r`n`r`nAccounts Payable`r`nAcme Parts Ltd" }
    @{ Tenant = 'felixstowe'; Name = 'docs-bol'; Type = 'DOCUMENT_REQUEST'
       From = 'Ingrid Larsen <ingrid.larsen@oceanline.example.com>'
       Subject = 'Bill of Lading copy for OOLU9876543'
       Body = "Hi,`r`n`r`nPlease send a copy of the Bill of Lading for OOLU9876543. Our bank needs it for the letter of credit.`r`n`r`nThank you,`r`nIngrid" }
    @{ Tenant = 'felixstowe'; Name = 'urgent-complaint'; Type = 'STATUS_UPDATE (escalated)'
       From = 'Peter Jones <peter.jones@tradelink.example.com>'
       Subject = 'URGENT - EVRU3219876 missed vessel AGAIN, production line stopping'
       Body = "This is the second time this month. EVRU3219876 was promised on the vessel and nobody told us it was rolled. Our production line stops on Thursday without these parts.`r`n`r`nI need a call from a manager TODAY and a firm ETA, otherwise we will be moving our business elsewhere.`r`n`r`nPeter Jones`r`nOperations Director, TradeLink" }
    @{ Tenant = 'felixstowe'; Name = 'rfq-attachment'; Type = 'RFQ + PDF'
       From = 'Anna Schmidt <anna.schmidt@logistics-eu.example.com>'
       Subject = 'Rate request - packing list attached'
       Body = "Hello,`r`n`r`nPlease find our packing list attached. We need a rate for LCL Hamburg to Felixstowe, delivery to Ipswich. Cargo ready next Monday.`r`n`r`nRegards,`r`nAnna Schmidt"
       Attachment = @{ Name = 'packing-list-HAM-FXT.pdf'
                       Lines = @('PACKING LIST', 'Shipper: Logistics EU GmbH, Hamburg', 'Consignee: Brightwater Retail Ltd, Ipswich IP3',
                                 'Route: Hamburg (DEHAM) to Felixstowe (GBFXT), LCL', '12 pallets, 4.8 CBM, gross weight 3,250 kg',
                                 'Commodity: kitchen appliances, non-hazardous', 'Cargo ready: 5 October 2026') } }
    @{ Tenant = 'felixstowe'; Name = 'general-hazmat'; Type = 'GENERAL (KB)'
       From = 'Priya Patel <priya.patel@startup.example.com>'
       Subject = 'Do you handle hazardous cargo?'
       Body = "Hi,`r`n`r`nWe are a new customer and will be importing lithium batteries (UN3480). Do you handle DG cargo, and what documents do you need from us?`r`n`r`nThanks,`r`nPriya" }
    @{ Tenant = 'felixstowe'; Name = 'spam'; Type = 'IRRELEVANT'
       From = 'Growth Team <deals@spam.example.com>'
       Subject = 'Boost your SEO ranking by 300% - limited offer'
       Body = "Dear business owner,`r`n`r`nOur SEO experts guarantee first page Google results in 30 days. Reply now for a free audit!`r`n`r`nUnsubscribe anytime." }

    # ------------- Midlands Haulage & Logistics (road haulage, tenant 90001) --------------
    @{ Tenant = 'midlands'; Name = 'rfq-ftl'; Type = 'RFQ'
       From = 'Sue Clark <sue.clark@acmeparts.example.com>'
       Subject = 'Price for full load Coventry to Glasgow'
       Body = "Hi,`r`n`r`nCould you price a full load (26 pallets, curtainsider) from Coventry CV3 to Glasgow G52 for collection Wednesday, delivery Thursday AM? Tail-lift needed at delivery.`r`n`r`nCheers,`r`nSue" }
    @{ Tenant = 'midlands'; Name = 'status-job'; Type = 'STATUS_UPDATE'
       From = 'Mark Evans <mark.evans@brightlogistics.example.com>'
       Subject = 'ETA for job MID-2026-0481?'
       Body = "Hi,`r`n`r`nWhat is the latest on MID-2026-0481? Rotterdam are asking when the trailer will arrive.`r`n`r`nMark" }
    @{ Tenant = 'midlands'; Name = 'booking-euro'; Type = 'BOOKING_CONFIRM'
       From = 'Rain Tamm <rain.tamm@balt-cargo.example.com>'
       Subject = 'Please book: part load Birmingham to Calais, Friday'
       Body = "Hello,`r`n`r`nPlease confirm booking of 8 pallets Birmingham B6 to Calais, collection Friday 08:00. T1 transit document will be arranged by us.`r`n`r`nBest regards,`r`nRain Tamm" }
    @{ Tenant = 'midlands'; Name = 'invoice-fuel'; Type = 'INVOICE_QUERY'
       From = 'Accounts <accounts@tradelink.example.com>'
       Subject = 'Fuel surcharge on INV-7810'
       Body = "Hello,`r`n`r`nINV-7810 shows an 18% fuel surcharge, but our agreement caps it at 12%. Please explain or reissue.`r`n`r`nThanks,`r`nTradeLink Accounts" }
    @{ Tenant = 'midlands'; Name = 'docs-pod'; Type = 'DOCUMENT_REQUEST'
       From = 'David Owen <david.owen@newcustomer.example.com>'
       Subject = 'POD needed for MID-2026-0523'
       Body = "Hi,`r`n`r`nCould you send the signed POD for MID-2026-0523 delivered to Solihull? Our customer disputes the delivery time.`r`n`r`nDavid" }
    @{ Tenant = 'midlands'; Name = 'urgent-damage'; Type = 'GENERAL (escalated)'
       From = 'Sara Ferry <sara.ferry@ferryside.example.com>'
       Subject = 'Damaged pallets on delivery - very unhappy'
       Body = "Your driver delivered 4 crushed pallets this morning and left before we could sign it as damaged. This is completely unacceptable. I want a claim form and a call back within the hour.`r`n`r`nSara Ferry`r`nFerryside Foods" }
    @{ Tenant = 'midlands'; Name = 'spam'; Type = 'IRRELEVANT'
       From = 'Promo <promo@marketing.example.com>'
       Subject = 'You have won a free fleet dashcam!'
       Body = "Congratulations! Click the link to claim your prize dashcam. Offer ends today." }

    # ---------------- Hard cases (Set = 'hard') - LLM eval only, not part of the live demo ----------------
    # Real-inbox mess the demo set does not cover. Expected outcomes: scripts/golden-expected.json.
    @{ Set = 'hard'; Tenant = 'felixstowe'; Name = 'hard-spaced-container'; Type = 'STATUS_UPDATE (id with spaces)'
       From = 'Gary Nolan <gary.nolan@eastport-supplies.example.com>'
       Subject = 'RE: RE: our PO 88231'
       Body = "Hi guys,`r`n`r`nFollowing up below - any news on the box for PO 88231? Container is HLXU 876432-1 I think. Need to book the haulier.`r`n`r`nCheers`r`nGary Nolan | Eastport Supplies | Tel 01473 555 019`r`n`r`n-----Original Message-----`r`nFrom: Gary Nolan`r`nSent: 02 November 2026 09:14`r`nSubject: our PO 88231`r`n`r`nHello, please advise when our goods arrive at Felixstowe." }
    @{ Set = 'hard'; Tenant = 'felixstowe'; Name = 'hard-two-asks'; Type = 'STATUS + DOCUMENT in one email'
       From = 'Tom Fisher <tom.fisher@harbourline.example.com>'
       Subject = 'CMAU5551234 - cleared yet? + customs paperwork'
       Body = "Hi,`r`n`r`nTwo things on CMAU5551234: is it cleared yet, and can you send me the customs paperwork for it so I can file it on our side?`r`n`r`nThanks,`r`nTom" }
    @{ Set = 'hard'; Tenant = 'felixstowe'; Name = 'hard-reefer-polite'; Type = 'urgent but polite'
       From = 'Lucy Hart <lucy.hart@coldchain-foods.example.com>'
       Subject = 'Temperature alarm on reefer SZLU9012345'
       Body = "Good afternoon,`r`n`r`nThe terminal app shows a temperature alarm on our reefer SZLU9012345 (frozen fish, set to -18C) at Felixstowe. Could you kindly ask the terminal to check the unit and the plug-in within the next two hours?`r`n`r`nThank you so much for your help,`r`nLucy Hart`r`nColdchain Foods" }
    # A real out-of-office reply carries this header (RFC 3834); ingestion goes by it, not by the subject.
    @{ Set = 'hard'; Tenant = 'felixstowe'; Name = 'hard-out-of-office'; Type = 'IRRELEVANT (auto-reply)'
       Headers = @{ 'Auto-Submitted' = 'auto-replied' }
       From = 'James Whitaker <james.whitaker@northgate-imports.example.com>'
       Subject = 'Automatic reply: Quote request: 3 x 40HC Ningbo to Felixstowe'
       Body = "Thank you for your email. I am out of the office until 17 November with limited access to email.`r`n`r`nFor urgent shipping matters please contact our logistics desk on logistics@northgate-imports.example.com.`r`n`r`nJames Whitaker" }
    @{ Set = 'hard'; Tenant = 'midlands'; Name = 'hard-angry-low'; Type = 'angry but not urgent'
       From = 'Karen Doyle <karen.doyle@westfield-retail.example.com>'
       Subject = 'Wrong PO on invoice - again'
       Body = "Honestly, this is the third invoice this quarter with the wrong PO number. INV-7902 says PO-1188 instead of PO-1211. I am getting tired of fixing your paperwork for you.`r`n`r`nPlease correct it when you get a chance - no rush on our side, it is not due until next month.`r`n`r`nKaren Doyle`r`nWestfield Retail" }
    @{ Set = 'hard'; Tenant = 'midlands'; Name = 'hard-loose-ref'; Type = 'STATUS_UPDATE (sloppy job ref)'
       From = 'Mark Evans <mark.evans@brightlogistics.example.com>'
       Subject = 'quick one'
       Body = "hi, any news on mid 2026 0481? customer chasing again. ta, mark" }
    @{ Set = 'hard'; Tenant = 'midlands'; Name = 'hard-german-rfq'; Type = 'RFQ in German'
       From = 'Stefan Weber <stefan.weber@weber-handel.example.com>'
       Subject = 'Anfrage Komplettladung Birmingham - Hamburg'
       Body = "Guten Tag,`r`n`r`nbitte senden Sie uns ein Angebot fuer eine Komplettladung (33 Paletten, Planensattel) von Birmingham nach Hamburg. Abholung am kommenden Dienstag, Zustellung bis Donnerstag.`r`n`r`nMit freundlichen Gruessen`r`nStefan Weber`r`nWeber Handel GmbH" }
    @{ Set = 'hard'; Tenant = 'midlands'; Name = 'hard-vague'; Type = 'STATUS_UPDATE without any reference'
       From = 'Paul Reid <paul.reid@reid-wholesale.example.com>'
       Subject = 'Re: Friday'
       Body = "Any update? Need to tell the warehouse." }
)

if ($List) {
    $scenarios | ForEach-Object {
        [pscustomobject]@{ Set = (Get-Set $_); Tenant = $_.Tenant; Scenario = $_.Name; Expected = $_.Type; Inbox = (Get-Inbox $_); Subject = $_.Subject }
    } | Format-Table -AutoSize
    return
}

# Minimal single-page PDF (Helvetica text lines) with a correct xref table, so the backend's
# PDFBox attachment parser extracts real text. ASCII only.
function New-DemoPdf([string[]]$Lines) {
    $escaped = $Lines | ForEach-Object { $_.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)') }
    $content = "BT /F1 12 Tf 60 760 Td 16 TL`n" + (($escaped | ForEach-Object { "($_) '" }) -join "`n") + "`nET"
    $objects = @(
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        "<< /Length $($content.Length) >>`nstream`n$content`nendstream"
    )
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("%PDF-1.4`n")
    $offsets = @()
    for ($i = 0; $i -lt $objects.Count; $i++) {
        $offsets += $sb.Length
        [void]$sb.Append("$($i + 1) 0 obj`n$($objects[$i])`nendobj`n")
    }
    $xref = $sb.Length
    [void]$sb.Append("xref`n0 $($objects.Count + 1)`n0000000000 65535 f `n")
    foreach ($o in $offsets) { [void]$sb.Append(('{0:D10} 00000 n ' -f $o) + "`n") }
    [void]$sb.Append("trailer`n<< /Size $($objects.Count + 1) /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n")
    return [System.Text.Encoding]::ASCII.GetBytes($sb.ToString())
}

$selected = $scenarios | Where-Object { ($Tenant -eq 'all' -or $_.Tenant -eq $Tenant) -and $_.Name -like $Scenario -and ($Set -eq 'all' -or (Get-Set $_) -eq $Set) }
if (-not $selected) {
    Write-Warning "No scenario matches Tenant='$Tenant' Scenario='$Scenario' Set='$Set'. Use -List."
    return
}

# The demo rate sheet (dev-seed V31) is stamped "valid until <load day> + 40..90 days", so on a stack
# that has been up for a couple of months every rate is expired and no RFQ draft carries a price.
# Push the working demo rates out again before sending. The two rows V31 expires on purpose (COSCO
# Shanghai-Southampton, P&O Birmingham-Dublin) are not listed, so they stay expired.
# Only a tenant that still has a demo inbox (*.example.com) is touched: once the harness is off
# (ROADMAP Gate B14) this is a no-op, so a real rate of the same carrier and route is never revived.
function Update-DemoRates {
    $sql = @'
UPDATE carrier_rate SET valid_until = CURRENT_DATE + 60
 WHERE valid_until < CURRENT_DATE + 14
   AND EXISTS (SELECT 1 FROM tenant_mailbox m
                WHERE m.tenant_id = carrier_rate.tenant_id AND m.username LIKE '%.example.com')
   AND (tenant_id, lower(carrier), lower(origin), lower(destination)) IN (
        (1, 'maersk', 'shanghai', 'felixstowe'), (1, 'msc', 'ningbo', 'felixstowe'),
        (1, 'hapag-lloyd', 'rotterdam', 'felixstowe'), (90001, 'dfds', 'birmingham', 'rotterdam'),
        (90001, 'ferrymasters', 'birmingham', 'hamburg'), (90001, 'stobart', 'birmingham', 'paris'))
RETURNING 1;
'@
    # The backend caches each tenant's valid rates in Redis for 10 minutes (RateSheetCache) and a direct
    # DB edit does not evict it. Sent over stdin to dodge native-argument quoting; the trailing '#'
    # swallows the CR PowerShell appends to piped text.
    $evict = '. /file-env.sh; REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli --scan --pattern "rateSheets::*" | REDISCLI_AUTH="$REDIS_PASSWORD" xargs -r redis-cli del >/dev/null #'
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # docker writes progress to stderr; the exit code decides
    Push-Location (Split-Path -Parent $PSScriptRoot)
    try {
        $out = $sql | docker compose exec -T postgres psql -U postgres -d smartfreight -AtXq -v ON_ERROR_STOP=1 -f - 2>$null
        if ($LASTEXITCODE -ne 0) { throw "psql exited with $LASTEXITCODE" }
        $extended = @($out | Where-Object { $_ -eq '1' }).Count
        if ($extended -gt 0) {
            $evict | docker compose exec -T redis sh 2>$null
            if ($LASTEXITCODE -ne 0) { throw "redis cache eviction exited with $LASTEXITCODE" }
            Write-Host "Extended $extended expired demo rate(s) to today + 60 days."
        }
    } catch {
        Write-Warning "Demo rates not refreshed ($($_.Exception.Message)) - RFQ drafts may come without a price."
    } finally {
        Pop-Location
        $ErrorActionPreference = $prev
    }
}
Update-DemoRates

$client = New-Object System.Net.Mail.SmtpClient($SmtpHost, $SmtpPort)
$total = @($selected).Count * $Repeat
$sent = 0
try {
    for ($round = 1; $round -le $Repeat; $round++) {
        foreach ($s in $selected) {
            $to = Get-Inbox $s
            $msg = New-Object System.Net.Mail.MailMessage
            $msg.From = New-Object System.Net.Mail.MailAddress($s.From)
            $msg.To.Add($to)
            $msg.Subject = if ($Repeat -gt 1) { "$($s.Subject) [#$round]" } else { $s.Subject }
            $msg.Body = $s.Body
            # System.Net.Mail adds no Message-ID, and ingestion drops mail without one.
            $domain = ($msg.From.Address -split '@')[1]
            $tag = if ($RunId) { "$RunId-$($s.Tenant)-$($s.Name)-" } else { '' }
            $msg.Headers.Add('Message-ID', "<demo-$tag$([guid]::NewGuid().ToString('N'))@$domain>")
            if ($s.Headers) { foreach ($h in $s.Headers.GetEnumerator()) { $msg.Headers.Add($h.Key, $h.Value) } }
            if ($s.Attachment) {
                # MailMessage.Dispose() disposes its attachments and their streams too.
                $stream = New-Object System.IO.MemoryStream(, (New-DemoPdf $s.Attachment.Lines))
                $msg.Attachments.Add((New-Object System.Net.Mail.Attachment($stream, $s.Attachment.Name, 'application/pdf')))
            }
            try { $client.Send($msg) } finally { $msg.Dispose() }
            $sent++
            Write-Host ("[{0,-10}] {1,-17} -> {2}  ({3})" -f $s.Tenant, $s.Name, $to, $s.Type)
            if ($DelaySeconds -gt 0 -and $sent -lt $total) { Start-Sleep -Seconds $DelaySeconds }
        }
    }
} finally {
    $client.Dispose()
}
Write-Host "`nSent $sent email(s). Tickets appear in the UI within seconds; replies land in http://localhost:8025"
