# SmartFreight Inbox — UK Deployment Roadmap

_Last updated: 2026-05-18_

---

## Project Overview

AI-powered email processing platform for UK logistics companies.
Automatically reads incoming freight enquiries, extracts shipment references,
looks up live status, and drafts professional replies for operator review.

**GitHub:** https://github.com/olegskel7-wq/smart-freight-inbox

---

## CI/CD Status

- **CI** (tests + build): active — triggers on every `git push` ✅
- **CD** (auto-deploy): configured, waiting for server secrets ⏳

Files: `.github/workflows/ci.yml` + `.github/workflows/deploy.yml`

---

## Server Setup Checklist

### 1. Rent VPS — UK-based server (GDPR requirement)

> ⚠️ **UK data residency is a core product promise** — "100% UK infrastructure".
> Do NOT use servers in Finland, Germany or the US. UK customers in legal, healthcare,
> and finance sectors require data to stay in the UK.

Recommended options:
- **AWS EC2 `eu-west-2` (London)** — t3.medium ~$35/mo, industry-standard, easy SSL
- **Hetzner UK** (London) — CX22 ~€5/mo if available in UK region (verify at hetzner.com)
- **OVHcloud UK** (London/Gravelines UK) — affordable, UK DC

Minimum spec: 2 vCPU, 4 GB RAM (Ollama requires 8+ GB — use GPU instance or demo-mode mock)

- OS: Ubuntu 24.04
- Get: IP address + root SSH access

### 2. Server bootstrap (run as root)
```bash
apt update && apt upgrade -y
apt install -y docker.io docker-compose-plugin git ufw
systemctl enable docker --now
ufw allow 22 && ufw allow 80 && ufw allow 443 && ufw allow 8080
ufw enable
git clone https://github.com/olegskel7-wq/smart-freight-inbox.git /home/ubuntu/smartfreight
```

### 3. Create SSH key pair (if not exists)
```bash
ssh-keygen -t ed25519 -C "smartfreight-deploy"
# Copy public key to server: ssh-copy-id ubuntu@SERVER_IP
```

### 4. Create .env on server
```bash
cd /home/ubuntu/smartfreight
cp .env.example .env
nano .env   # fill in values below
```

#### Required .env values

| Variable | Where to get |
|----------|-------------|
| `POSTGRES_PASSWORD` | Invent a strong password |
| `IMAP_HOST` | `imap.gmail.com` (Gmail) or Mailtrap |
| `IMAP_USER` | Email address for inbox monitoring |
| `IMAP_PASSWORD` | Gmail App Password (not account password) |
| `SMTP_HOST` | `smtp.gmail.com` or Mailtrap |
| `SMTP_PORT` | `587` |
| `SMTP_USER` | Same as IMAP_USER |
| `SMTP_PASSWORD` | Same as IMAP_PASSWORD |
| `PORT_SERVICE_API_KEY` | Any secret string e.g. `sf-port-secret-2026` |
| `GRAFANA_PASSWORD` | Invent a password |

#### Gmail App Password setup
1. Google Account → Security → 2-Step Verification (must be ON)
2. Security → App passwords → create "SmartFreight"
3. Use the 16-char password in IMAP_PASSWORD and SMTP_PASSWORD

#### Mailtrap (alternative — no real emails, good for demo)
1. Register at mailtrap.io (free)
2. Inbox → SMTP Settings → copy host/port/user/password

### 5. GitHub Secrets (for auto-deploy on push)
GitHub → repo → Settings → Secrets and variables → Actions:

| Secret name | Value |
|-------------|-------|
| `DEPLOY_HOST` | Server IP |
| `DEPLOY_USER` | `ubuntu` |
| `DEPLOY_SSH_KEY` | Contents of `~/.ssh/id_ed25519` (private key) |
| `DEPLOY_PATH` | `/home/ubuntu/smartfreight` |
| `DEPLOY_ENV` | Full contents of `.env` file |

### 6. First manual deploy
```bash
cd /home/ubuntu/smartfreight
docker compose up -d --build
# Wait ~2 min, then check:
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

Access points after deploy:
- App: `http://SERVER_IP:8080/login`
- Grafana: `http://SERVER_IP:3000` (admin / GRAFANA_PASSWORD)
- Demo login: `operator@smartfreight.com` / `Operator1Pass` (via Keycloak)

---

## Demo Mode (TODO — priority before first client demo)

**Problem:** Ollama (AI) requires GPU server (~€70+/mo). CX22 has no GPU.

**Solution:** Add `demo` Spring profile that mocks AI responses.

**What to build:**
- `DemoAiExtractionService` — returns hardcoded reference number
- `DemoAiDraftingService` — returns hardcoded draft text
- `docker-compose.demo.yml` — excludes ollama service
- Run with: `docker compose -f docker-compose.yml -f docker-compose.demo.yml up -d`

**Effort:** ~1 day

---

## Domain (optional, do when first client conversation starts)

- Register: `smartfreight.co.uk` or `smartfreight.io` (~£10/year)
- Registrars: Namecheap, Cloudflare, 123-reg
- DNS: A record → server IP
- SSL: Replace nginx self-signed cert with Let's Encrypt
  ```bash
  apt install certbot
  certbot certonly --standalone -d yourdomain.co.uk
  ```

---

## Go-to-Market Priorities

1. ✅ CI/CD configured
2. ⏳ Demo mode (mock AI, no GPU needed)
3. ⏳ Rent Hetzner CX22 + deploy
4. ⏳ Record Loom video using `smartfreight-demo.html`
5. ⏳ Buy domain + put landing page online
6. ⏳ LinkedIn outreach: 20–30 UK freight SMEs
   - Target titles: Operations Director, IT Manager, Logistics Manager
   - Associations: BIFA (bifa.org), CILT UK (ciltuk.org.uk)

---

## Target Client Profile

- UK road freight / parcel / 3PL company
- 20–200 employees
- Receives 20+ shipment status emails per day
- No existing automation for email responses
- Budget: £200–500/month for operational software

---

## Tech Stack (for client conversations)

- **Language:** Java 17 / Spring Boot
- **Database:** PostgreSQL (your data, your server)
- **AI:** Runs locally — no data sent to OpenAI or any cloud
- **Deployment:** Docker — installs in under an hour
- **Monitoring:** Grafana dashboard included
- **Compliance:** UK GDPR friendly (on-premise, soft delete, audit log)
