# GoDoc Take-Home — Consultation Booking System

A simplified consultation booking flow (assessment **Option 1**): patients browse a
doctor's available slots and book one, with the system staying correct when many
requests fight over the same slot at the same time.

The centerpiece is the **double-booking defense** and the automated test that proves it:
50 truly concurrent requests for one slot always produce exactly one booking, 49 clean
`409 Conflict` responses, and exactly one active row in the database.

**Live demo:** _added after deploy — see [Deploying to Render](#deploying-to-render-free-tier)._
Note: on Render's free tier the instance spins down when idle, so the **first request can
take up to a minute** (JVM cold start). That is the platform, not the app.

---

## Tech stack and why

| Choice | Why | Trade-off considered |
|---|---|---|
| **Java 21 + Spring Boot 3.5** | The stack I'm most productive in — and the assessment's core problem (transactional correctness) is exactly where JPA/Spring's tooling is strongest: declarative transactions, `@Version` optimistic locking, translated constraint violations. | A Node/TypeScript stack would share one language with the UI and deploy lighter, but the double-booking race is won **in the database**, not the app language — no stack removes the hard part, so fluency wins. |
| **PostgreSQL 16** | Supports **partial unique indexes**, which give a one-line, airtight double-booking guarantee that holds across any number of app instances. | MySQL lacks partial unique indexes (would need a generated-column workaround). SQLite/H2 were rejected even for tests — they don't honor the same constraint/locking semantics, which is precisely what must be tested. |
| **Flyway** | Schema is versioned, reviewable SQL; Hibernate never generates DDL (`ddl-auto: none`). | — |
| **Testcontainers** | Integration and race tests run against real Postgres 16, the same engine as production. | Slower than H2-style tests; worth it because the concurrency guarantees under test live in Postgres itself. |
| **Vue 3 + TypeScript + Vite** | Thin demo UI in the frontend stack I know best; typed against the API DTOs. | Kept deliberately minimal — the brief's weight is on the backend. |
| **Docker (single image)** | The Vue build is baked into the Spring Boot jar: one artifact, one Render service, same origin (no CORS anywhere). | Separate static-site hosting would give CDN caching, at the cost of a second service and CORS/config surface. |

---

## How double-booking is prevented

Layered defense, in order of authority:

**1. A partial unique index is the final arbiter** ([V1__schema.sql](backend/src/main/resources/db/migration/V1__schema.sql)):

```sql
CREATE UNIQUE INDEX ux_active_booking_per_slot
    ON booking (slot_id)
    WHERE status IN ('PENDING', 'CONFIRMED');
```

Two transactions inserting an active booking for the same slot cannot both commit —
regardless of how requests interleave or how many app instances are running. The loser's
violation is translated into `409 Conflict`.

Availability is **derived** from this same rule: a slot is available iff it has no active
booking. There is deliberately no `booked` flag on `slot` — a flag would be a second
source of truth to keep consistent. A nice consequence: cancelling a booking frees the
slot with zero extra bookkeeping, because `CANCELLED` falls outside the index predicate
(covered by an integration test).

**2. Optimistic locking guards racing transitions.** `Booking` carries a `@Version`
column; when a confirm and a cancel (or two confirms) race, exactly one commits and the
other gets `409`. Without this, last-write-wins could silently cancel a confirmed booking.

**3. Idempotency keys make retries safe.** `POST /bookings` requires an
`Idempotency-Key` header, unique per `(patient, key)`. A replay — user double-click,
network retry — returns the original booking (`200`) instead of attempting a duplicate.
Reusing a key for a *different* slot is rejected (`422`).

### Alternatives considered (and why not)

- **Pessimistic `SELECT … FOR UPDATE` on the slot row** — simpler to reason about and a
  fine choice at this scale, but it serializes all writers on a hot slot and holds locks
  for the whole transaction. The constraint approach lets the happy path proceed
  lock-free and only punishes actual conflicts. (With the index in place as backstop,
  adding `FOR UPDATE` later is an additive optimization, not a redesign.)
- **Distributed locks (Redis)** — new infrastructure, new failure modes (lock expiry vs.
  long transactions), and you still need the DB constraint as the backstop anyway.
- **`SERIALIZABLE` isolation** — pushes conflict handling into retry loops on every
  transaction; far more machinery than one index for this access pattern.

### The proof

[`BookingRaceTest`](backend/src/test/java/com/godoc/consult/booking/BookingRaceTest.java)
fires 50 simultaneous booking requests (latch-synchronized threads, real HTTP, real
Postgres) at one slot and asserts: exactly one `201`, exactly forty-nine `409`s, exactly
one active row in the DB. A second test races two `confirm`s and asserts exactly one
winner. These run in the normal `./mvnw test` suite.

---

## Booking state machine

```
 (book) ──► PENDING ──confirm──► CONFIRMED ──complete──► COMPLETED
               │                     │
               └───────cancel────────┴──► CANCELLED
```

| From | Action | To | Anything else |
|---|---|---|---|
| — | book | PENDING | `409` if slot taken |
| PENDING | confirm | CONFIRMED | invalid transition → `409` |
| PENDING, CONFIRMED | cancel | CANCELLED | frees the slot |
| CONFIRMED | complete | COMPLETED | clinic action (see scope cuts) |

`CANCELLED` and `COMPLETED` are terminal. Transitions are enforced in exactly one place —
the `Booking` entity's methods ([Booking.java](backend/src/main/java/com/godoc/consult/booking/Booking.java));
there is no status setter, so the state machine cannot be bypassed from services or
controllers.

---

## Running locally

Prerequisites: **JDK 21+**, **Docker Desktop** (for Postgres and Testcontainers),
**Node 20+** (only for frontend dev).

```bash
# 1. Database (host port 5433, to avoid clashing with any local Postgres)
docker compose up -d

# 2. API on :8080  (Windows: mvnw.cmd)
cd backend && ./mvnw spring-boot:run
```

On startup, Flyway migrates the schema and seed data (3 doctors, 3 patients), and an
idempotent seeder generates bookable slots (09:00–17:00 UTC, weekdays, next 14 days).

- Full app (UI + API from one jar image):
  `docker build -t consult . && docker run -p 8080:8080 -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5433/consult -e SPRING_DATASOURCE_USERNAME=consult -e SPRING_DATASOURCE_PASSWORD=consult consult`
- Frontend dev server with hot reload: `cd frontend && npm install && npm run dev`
  → http://localhost:5173 (proxies `/api` to :8080).
- No local Postgres needed at all: `cd backend && ./mvnw spring-boot:test-run` boots the
  app against a throwaway Testcontainers database.

To see the race handling in the UI: open two browser tabs, act as two different patients,
and book the same slot — the loser gets a "slot was just taken" toast and refreshed
availability.

## Running tests

```bash
cd backend && ./mvnw test        # Docker Desktop must be running (Testcontainers)
```

25 tests: pure unit tests for the state machine (every valid and invalid transition),
integration tests for the booking flow and error contract over real HTTP + Postgres, and
the two race tests described above.

---

## API overview

Base path `/api/v1`. Errors are RFC 7807 `application/problem+json`.

Auth is stubbed: requests identify the caller with an **`X-Patient-Id`** header (seeded
patient UUIDs below). This stands in for a real authenticated principal — see scope cuts.

| Method | Path | Purpose | Notable responses |
|---|---|---|---|
| GET | `/doctors` | List doctors | |
| GET | `/doctors/{id}/slots?from=&to=` | Available slots (ISO-8601 instants; default: next 7 days) | `404` unknown doctor |
| GET | `/patients` | Seeded patients (demo-only, powers the UI's "act as" picker) | |
| POST | `/bookings` | Book a slot. Headers: `X-Patient-Id`, `Idempotency-Key`; body `{"slotId": "…"}` | `201` created · `200` idempotent replay · `409` slot taken · `422` past slot / key reuse mismatch · `404` unknown slot · `401` unknown patient |
| GET | `/bookings` | Caller's bookings (joined with slot + doctor) | |
| GET | `/bookings/{id}` | One booking, owner-scoped | `404` (also for other patients' bookings — no id leaking) |
| POST | `/bookings/{id}/confirm` | PENDING → CONFIRMED | `409` invalid transition or lost optimistic-lock race |
| POST | `/bookings/{id}/cancel` | active → CANCELLED (frees slot) | `409` invalid transition |
| POST | `/bookings/{id}/complete` | CONFIRMED → COMPLETED | `409` invalid transition |

Seeded ids for quick testing:

- Doctors: `11111111-…` Dr. Aisha Rahman (GP), `22222222-…` Dr. Ben Tan (Dermatology), `33333333-…` Dr. Chloe Lim (Paediatrics) — full UUIDs repeat the digit.
- Patients: `aaaaaaaa-…` Alice Ng, `bbbbbbbb-…` Bob Ooi, `cccccccc-…` Carol Wong.

```bash
DOC=11111111-1111-1111-1111-111111111111
SLOT=$(curl -s "localhost:8080/api/v1/doctors/$DOC/slots" | jq -r '.[0].id')
curl -i -X POST localhost:8080/api/v1/bookings \
  -H "X-Patient-Id: aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" \
  -H "Idempotency-Key: try-1" -H "Content-Type: application/json" \
  -d "{\"slotId\":\"$SLOT\"}"                      # 201 — repeat it: 200, same booking
```

---

## Deploying to Render (free tier)

1. **Database**: New → PostgreSQL, free plan. ⚠️ Render's free Postgres **expires 30 days
   after creation** — create it when you're ready to demo, not at project start.
2. **Web service**: New → Web Service → this repo. Render auto-detects the root
   `Dockerfile`. Instance type: free.
3. **Environment variables** (from the database's *internal* connection info — note the
   URL must be the JDBC form, not Render's `postgres://` string):
   - `SPRING_DATASOURCE_URL` = `jdbc:postgresql://<internal-host>:5432/<db>`
   - `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
4. **Health check path**: `/actuator/health`.
5. Deploy. Flyway migrates and seeds on boot; the UI is served at the service root.

The JVM is already sized for the 512MB free instance via `JAVA_TOOL_OPTIONS` in the
Dockerfile.

---

## Assumptions, scope cuts, and known limitations

Deliberate cuts, each with the reasoning and the path to production:

- **Authentication/authorization is stubbed** via `X-Patient-Id` + seeded patients.
  Chosen so the interesting logic stays reviewable; the header resolves to a validated
  patient exactly where a JWT principal would. Path: Spring Security + OIDC, derive the
  patient from the token, drop the `/patients` listing endpoint.
- **PENDING bookings never expire.** An abandoned PENDING booking holds its slot
  indefinitely — a real slot leak. Path: `expires_at` on PENDING rows, a scheduled sweep
  cancelling stale ones, and (since the sweep can lag) treating expired holds as
  available at read time. Kept out to protect the core's clarity; called out because it
  is the first thing I would build next.
- **`complete` is triggered by the patient** in this demo; in reality it's a
  clinic/doctor action after the consultation. The endpoint exists to finish the state
  machine; role-based authz is part of the auth cut above.
- **Single timezone (UTC)** everywhere; the UI renders times in the browser's locale.
  Multi-timezone clinics would store clinic zone per doctor and localize slot generation.
- **Slot schedules are seeded**, not managed. Doctors get uniform weekday hours via an
  idempotent startup seeder; real scheduling (doctor-managed calendars, leave, breaks) is
  a separate feature with its own domain.
- **No payments, notifications, or rescheduling.** Reschedule = cancel + book (already
  race-safe); an atomic reschedule would be one transaction updating with the same
  constraint protection.
- **No pagination or rate limiting** — trivial data volumes here; both are additive.

### Scaling notes (how this grows without a rewrite)

- **Stateless app + DB-enforced invariants** means horizontal scaling is just "run more
  instances"; correctness never depended on a single JVM.
- **Package-by-feature modular monolith** (`booking`, `doctor`, `patient`, `common`) with
  one-way dependencies (`booking → doctor/patient`; the doctor package knows nothing
  about bookings). Any package can be split into a service along its existing seam —
  availability queries already live on the booking side for exactly this reason.
- **Hot-slot contention** (flash-sale-style load on one doctor) would first get
  `SELECT … FOR UPDATE SKIP LOCKED`-style claiming or per-slot advisory locks in front of
  the same index backstop — additive changes, not redesigns.

## Project layout

```
backend/   Spring Boot API — com.godoc.consult.{booking,doctor,patient,common}
frontend/  Vue 3 + TypeScript UI (Vite)
Dockerfile Multi-stage: frontend dist → Spring static resources → single JRE image
docker-compose.yml  Local Postgres 16 (host port 5433)
```
