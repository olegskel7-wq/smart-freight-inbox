<#
.SYNOPSIS
    Demo mail harness traffic generator (Phase 4.8 S2).

.DESCRIPTION
    Drops realistic UK freight/haulage customer emails into the demo tenants' GreenMail
    inboxes over SMTP. The backend's IMAP IDLE adapters pick them up and run the REAL
    pipeline: ingestion -> classification -> extraction -> draft -> (auto-)reply. Replies
    land in Mailpit: http://localhost:8025.

    Prerequisites:
      docker compose --profile demo up -d greenmail mailpit
      backend running with DEMO_MAIL_ENABLED=true (+ DEMO_IMAP_HOST/DEMO_SMTP_HOST=localhost
      when run from the IDE) and MAILBOX_ENCRYPTION_KEY set.

.EXAMPLE
    .\scripts\demo-mail.ps1 -List
    .\scripts\demo-mail.ps1                                # every scenario, both tenants
    .\scripts\demo-mail.ps1 -Tenant midlands -Scenario rfq*
    .\scripts\demo-mail.ps1 -Repeat 5 -DelaySeconds 20     # slow drip for a live demo
#>
param(
    [ValidateSet('all', 'felixstowe', 'midlands')]
    [string]$Tenant = 'all',
    [string]$Scenario = '*',
    [int]$Repeat = 1,
    [int]$DelaySeconds = 2,
    [string]$SmtpHost = 'localhost',
    [int]$SmtpPort = 3025,
    [switch]$List
)

$ErrorActionPreference = 'Stop'

$inboxes = @{
    felixstowe = 'ops@felixstowe-freight.example.com'
    midlands   = 'dispatch@midlands-haulage.example.com'
}

# Container / job refs match the port-service seed (V2/V3 sea containers, V7 road jobs),
# so status enquiries resolve to real tracking data.
$scenarios = @(
    # ---------------- Felixstowe Freight Solutions (sea freight, tenant 1) ----------------
    @{ Tenant = 'felixstowe'; Name = 'rfq-fcl'; Type = 'RFQ'
       From = 'James Whitaker <james.whitaker@northgate-imports.example.com>'
       Subject = 'Quote request: 3 x 40HC Ningbo to Felixstowe, early November'
       Body = "Hello,`r`n`r`nCould you quote for 3 x 40HC, FCL, Ningbo to Felixstowe, cargo ready 4 November? Commodity is flat-pack furniture, approx 18 tonnes per box. We would need delivery to our warehouse in Chelmsford (CM2).`r`n`r`nPlease include THC and customs clearance.`r`n`r`nKind regards,`r`nJames Whitaker`r`nNorthgate Imports Ltd" }
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
)

if ($List) {
    $scenarios | ForEach-Object {
        [pscustomobject]@{ Tenant = $_.Tenant; Scenario = $_.Name; Expected = $_.Type; Subject = $_.Subject }
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

$selected = $scenarios | Where-Object { ($Tenant -eq 'all' -or $_.Tenant -eq $Tenant) -and $_.Name -like $Scenario }
if (-not $selected) {
    Write-Warning "No scenario matches Tenant='$Tenant' Scenario='$Scenario'. Use -List."
    return
}

$client = New-Object System.Net.Mail.SmtpClient($SmtpHost, $SmtpPort)
$client.EnableSsl = $false
$sent = 0
try {
    for ($round = 1; $round -le $Repeat; $round++) {
        foreach ($s in $selected) {
            $to = $inboxes[$s.Tenant]
            $msg = New-Object System.Net.Mail.MailMessage
            $msg.From = New-Object System.Net.Mail.MailAddress($s.From)
            $msg.To.Add($to)
            $msg.Subject = if ($Repeat -gt 1) { "$($s.Subject) [#$round]" } else { $s.Subject }
            $msg.Body = $s.Body
            # System.Net.Mail adds no Message-ID, and ingestion drops mail without one.
            $domain = ($msg.From.Address -split '@')[1]
            $msg.Headers.Add('Message-ID', "<demo-$([guid]::NewGuid().ToString('N'))@$domain>")
            $stream = $null
            if ($s.Attachment) {
                $stream = New-Object System.IO.MemoryStream(, (New-DemoPdf $s.Attachment.Lines))
                $msg.Attachments.Add((New-Object System.Net.Mail.Attachment($stream, $s.Attachment.Name, 'application/pdf')))
            }
            try {
                $client.Send($msg)
            } finally {
                $msg.Dispose()
                if ($stream) { $stream.Dispose() }
            }
            $sent++
            Write-Host ("[{0,-10}] {1,-17} -> {2}  ({3})" -f $s.Tenant, $s.Name, $to, $s.Type)
            if ($DelaySeconds -gt 0) { Start-Sleep -Seconds $DelaySeconds }
        }
    }
} finally {
    $client.Dispose()
}
Write-Host "`nSent $sent email(s). Tickets appear in the UI within seconds; replies land in http://localhost:8025"
