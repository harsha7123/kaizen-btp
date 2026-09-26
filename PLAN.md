# Kaizen on SAP BTP: build plan

**Team:** Harsha (owner: accounts, SAP access, testing on real devices, demos) + Claude (code, tests, gates, docs).
**Strategy:** build the real multi-tenant product from day one, run the demo on the free **BTP Trial**, and swap trial stand-ins for paid services only when a customer pays. Each swap is one adapter, never a rewrite.

Rules for every phase: `ponytail` (the least code that works, CAP/Fiori built-ins first) and `unlazy` (a phase is done only when its `GATES.md` passes on `--reverify`).

---

## Target architecture (same code for demo and product)

```
Browser / phone ── App Router (tenant from subdomain) ── XSUAA (tenant-mode: shared)
                        │
                 CAP app (one codebase, all tenants) ── MTX sidecar (@sap/cds-mtxs)
                        │                                   │
              HANA Cloud: one HDI container per tenant ◄── Service Manager
                        │
      per-tenant Destination ──► customer's own S/4HANA (API_EQUIPMENT, API_MAINTNOTIFICATION, ...)
```

### Trial stand-ins vs product services

| Need | Demo on BTP Trial (free) | Product (paid) | Swap cost |
|---|---|---|---|
| Tenant DBs | HANA Cloud trial + HDI per tenant | HANA Cloud | none |
| Photos | HANA `LargeBinary` column | Object Store, path `/{tenant}/{kaizen}/` | 1 handler |
| Events / notifications | CAP in-process `emit` + in-app inbox | Event Mesh + Work Zone notifications | 1 adapter |
| SLA escalation | timer inside the app, per tenant | Job Scheduling service | 1 adapter |
| AI drafting | swappable provider: AI Core trial if it offers the generative AI hub in your region, otherwise a stub provider | AI Core / generative AI hub (Orchestration) | config |
| S/4 master data | seeded CSV (`db/data`) + optional SAP Business Accelerator Hub sandbox | customer's S/4 via their destination | config |
| Dashboards | Fiori analytical list page on CAP OData V4 | + SAP Analytics Cloud via Datasphere | additive |

> Trial caveats (check in your cockpit, they change): the account expires after about 90 days unless extended, HANA Cloud trial stops every night (restart it before a demo), and region/service availability varies.

---

## Phases

| # | Phase | Who | Deliverable | Exit gate | Est. |
|---|---|---|---|---|---|
| 0 | Accounts & tools | Harsha | BTP Trial, HANA Cloud trial instance, CF space; Node 24, `@sap/cds-dk`, `cf` CLI + MultiApps plugin, `mbt`; GitHub repo | `cds v` and `cf target` work: **done** (us10 trial, HANA `kaizen-hana` mapped to CF space dev, smoke test passed on HANA) | done |
| **1** | **Core backend (local, multi-tenant)** | Claude | data model, workflow state machine, routing, verification gate, roles, audit trail, 2 local tenants | `GATES.md` phase 1: **done** | done |
| 2 | Shop-floor capture app | Claude, Harsha tests on phone | PWA at `/capture/` (plain web, no UI5 bootstrap: fast + fully offline): QR scan, camera (Before/After), voice-to-text, offline queue, submit in < 60 s | submit works offline then syncs; installable PWA (Lighthouse 12 dropped its PWA audit, so checked directly): **built, gates G1-G2 pass, G3 = phone test** | in test |
| 3 | Approvals & management | Claude | Fiori Elements list/object pages (drafts), my-inbox, KPI page (count, cycle time, verified EUR by plant/pillar) | Maria scenario clickable end to end: **done** (G1-G4, incl. HANA and your click-through) | done |
| 4 | AI assist | Claude | provider interface + photo-to-draft, 5-Why helper, A3 write-up, duplicate check | stub and real provider pass the same tests: **built; G1-G3 pass (stub + AI Core adapter vs. local imitation, clickable demo, HANA); G4 real AI Core pending access** | done (stub) |
| 5 | S/4 integration | Claude + Harsha (API key) | `cds import` of Equipment/WorkCenter/Maintenance Notification APIs, mocked locally, destination-based remotely; `pmWriteBack` feature toggle | QR scan resolves live equipment from sandbox: **built; G1-G2 pass (mocked S/4, browser); G3 live sandbox waits for your API key** | done (mock) |
| 6 | Deploy demo to Trial | Harsha runs, Claude prepares | `cds add hana,xsuaa,approuter,mta`, `mbt build`, `cf deploy`; second trial subaccount subscribes as "customer 2" | two subdomains, isolated data, demo script runs | 2-3 days |
| 7 | Gamification & polish | Claude | points, badges, plant leaderboard, horizontal deployment of closed kaizens | tests + demo | 3 days |

**Demo-ready: about 4 weeks.**

### Product track (after first paying customer / pilot)

1. Move to a paid global account (or keep the free-tier plans that exist there), separate dev / test / prod subaccounts.
2. Swap stand-ins: Object Store, Event Mesh, Job Scheduling, generative AI hub (one adapter each, tests unchanged).
3. Per-tenant onboarding: subscriber creates the S/4 destination (+ Cloud Connector for on-prem), roles mapped to their IdP groups.
4. Operations: CI/CD, Cloud Logging, Alert Notification, per-tenant AI usage metering and quotas, backups.
5. Security review (XSUAA scopes, tenant isolation tests in CI, data masking before AI), then SAP Store listing if wanted.

---

## Demo script (the "Maria" scenario)

1. Maria (operator, Hamburg) scans P-1042, takes a Before photo, AI drafts the text, submits.
2. Sam (supervisor) approves, Klaus (CI manager) approves and starts it with 1 task.
3. Klaus tries to close: blocked by the verification gate (no After photo, no verified benefit, open task).
4. Klaus adds the After photo, a verified OEE benefit (78.2% to 83.5%, EUR 14,200/yr), finishes the task, and closes it.
5. Dashboard shows the saving. Switch to tenant 2: none of this is visible.

## Local development

```
npm install && (cd mtx/sidecar && npm install)
npm test                         # workflow tests (single tenant, in-memory)
node test/verify-tenants.mjs     # real MTX sidecar, tenants t1/t2, isolation proof
npm run watch                    # single-tenant dev server on :4004 (users: maria, sam, klaus, petra, eva, admin)
```

Against real HANA (after Phase 0): start `kaizen-hana` in HANA Cloud Central (it stops nightly), then `npm run hybrid` (uses the `kaizen-smoke-db` HDI container bound in `.cdsrc-private.json`; redeploy the model with `cds deploy --to hana --profile hybrid`).

Multi-tenant by hand: `npm run sidecar` in one terminal, `npm run watch:mtx` in another, then `cds subscribe t1 --to http://localhost:4005 -u yves:` (and `t2`). `erin` belongs to t2.
