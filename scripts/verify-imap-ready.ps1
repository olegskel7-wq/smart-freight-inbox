# Verifies PostgreSQL and Ollama are reachable before IMAP + LLM testing.
# Does not require IMAP credentials.

$ErrorActionPreference = "Continue"
$ok = $true

Write-Host "=== Smart Freight Inbox: pre-flight check ===" -ForegroundColor Cyan

# PostgreSQL (host port from docker-compose)
try {
    $tcp = Test-NetConnection -ComputerName localhost -Port 5433 -WarningAction SilentlyContinue
    if ($tcp.TcpTestSucceeded) {
        Write-Host "[OK] PostgreSQL reachable on localhost:5433" -ForegroundColor Green
    } else {
        Write-Host "[FAIL] localhost:5433 not accepting connections. Run: docker compose up -d postgres" -ForegroundColor Red
        $ok = $false
    }
} catch {
    Write-Host "[FAIL] Could not test port 5433: $_" -ForegroundColor Red
    $ok = $false
}

# Ollama
try {
    $r = Invoke-WebRequest -Uri "http://localhost:11434/api/tags" -UseBasicParsing -TimeoutSec 5
    if ($r.StatusCode -eq 200) {
        Write-Host "[OK] Ollama reachable on http://localhost:11434/api/tags" -ForegroundColor Green
        $json = $r.Content | ConvertFrom-Json
        $names = @($json.models | ForEach-Object { $_.name })
        foreach ($need in @("qwen2.5:7b", "llama3.1:8b")) {
            $found = $names | Where-Object { $_ -like "*$need*" }
            if ($found) {
                Write-Host "      Model present: $need" -ForegroundColor Green
            } else {
                Write-Host "      [WARN] Model not listed: $need (pull inside ollama container if needed)" -ForegroundColor Yellow
            }
        }
    }
} catch {
    Write-Host "[FAIL] Ollama not reachable: $_" -ForegroundColor Red
    Write-Host "       Run: docker compose up -d ollama" -ForegroundColor Yellow
    $ok = $false
}

Write-Host ""
Write-Host "IMAP: set IMAP_HOST, IMAP_USER, IMAP_PASSWORD in IntelliJ Run Configuration." -ForegroundColor Cyan
Write-Host "See: docs/IMAP_REAL_MAIL_SETUP.md" -ForegroundColor Cyan
Write-Host ""

if (-not $ok) { exit 1 }
exit 0
