# port-service / item-service
## CLAUDE.md — AI Assistant Context

This microservice is part of the Universal AI Email Automation Platform.  
**Full project context:** `C:\Users\olegs\Downloads\inbox\inbox\CLAUDE.md`

---

## Purpose
REST microservice — data backend for the inbox application.  
Current implementation: containers at Felixstowe port.  
**Plan:** universalise into item-service (any sector).

## Endpoint
```
GET /api/v1/containers/{identifier}
Protected by: X-API-Key header (timing-safe comparison)
```

## Key Files
```
ContainerController   → REST endpoint
ContainerService      → normalisation, transformation
ContainerRepository   → Spring Data JPA
Container (entity)    → JPA entity
ContainerResponse     → DTO (record)
ContainerStatus       → enum: IN_TRANSIT, AT_PORT, CUSTOMS, RELEASED, DELIVERED
StatusUpdateScheduler → status update simulation (every 5 min, ShedLock)
ApiKeyFilter          → timing-safe API Key authentication
SecurityConfig        → Spring Security stateless
ShedLockConfig        → distributed lock config
```

## Flyway Migrations
```
V1 — table schema + index
V2 — seed data (7 containers)
V3 — data expansion
V4 — CHECK constraint on status (DB-level validation)
V5 — shedlock table
V6 — drop redundant index idx_container_number
```

## Run Configuration (IntelliJ)
```
PORT_SERVICE_API_KEY=smartfreight-port-secret
DB_HOST=localhost / DB_PORT=5435
VM: -Djava.net.preferIPv4Stack=true
```

### Before starting — bring up the DB (from the inbox/inbox folder):
```
docker compose up -d postgres port-db
```
postgres = inbox DB (5433, smartfreight), port-db = port-service DB (5435, port_system)

### ⚠️ Flyway: DO NOT edit applied migrations
Changing even a comment in an already-applied migration breaks checksum validation
(`Migration checksum mismatch`). If you did — repair:
```
docker exec inbox-port-db-1 psql -U postgres -d port_system \
  -c "UPDATE flyway_schema_history SET checksum=<new> WHERE version='N';"
```
New schema changes — only via a new migration (V7, V8, ...).

## Known Issues
- Slow startup (~20 sec) → solution: Java 25 (lazy-initialization tried — did not help, removed)

## Done in Code Review (25 May 2026)
- ✅ findAll() in StatusUpdateScheduler → pagination PageRequest.of(0, 1000)
- ✅ API versioned: /api/containers → /api/v1/containers (inbox updated in sync)
- ✅ 404 → RFC 7807 ProblemDetail (ContainerNotFoundException + GlobalExceptionHandler)
- ✅ Actuator locked down: health (only when-authorized details) + /actuator/prometheus open, rest under X-API-Key
- ✅ Fail-fast on default key dev-local-key in prod profile (ApiKeyStartupValidator)
- ✅ Removed duplicate index idx_container_number (migration V6)
- ⚠️ Prometheus cannot send a custom X-API-Key → /actuator/prometheus is protected by network isolation in prod, not by key

## Next Step (Phase 0)
Rename Container → TrackedItem, make statuses configurable from DB

*Last updated: 26 May 2026*
