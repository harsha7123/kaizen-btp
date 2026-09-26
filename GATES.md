# Gates: phase 1, core multi-tenant backend

OWNS: db/**, srv/**, test/**, package.json, PLAN.md, GATES.md, readme.md

Scope: CAP backend with the kaizen data model, workflow state machine, table-driven routing, verification gate, role restrictions and audit trail, proven isolated across two local tenants.

- [x] G1: the CDS model compiles to SQL with all kaizen tables
  CHECK: npx cds compile srv --to sql
  EXPECT: /^(?=[\s\S]*CREATE TABLE kaizen_Kaizens \()(?=[\s\S]*CREATE TABLE kaizen_Photos \()(?=[\s\S]*CREATE TABLE kaizen_Tasks \()(?=[\s\S]*CREATE TABLE kaizen_Benefits \()(?=[\s\S]*CREATE TABLE kaizen_StatusHistory \()(?=[\s\S]*CREATE TABLE kaizen_WorkflowRoutes \()/
  EVIDENCE: automatic-evidence=v1; definition-sha256=062b49597be538ff7109b87458c705fa1e6fc63d9d0b6911b615a0522386c95e; exit=0; EXPECT=matched; output-sha256=74bfaf0680d913673be5ee5c7d5588d7a897b729af6d43f3153109c971102126; output-bytes=7310; shell=/bin/sh; cwd=/home/claude/kaizen-btp; path=fce7924b079f/15 entries

- [x] G2: workflow, routing, verification gate and role tests all pass
  CHECK: node --test --test-reporter=tap
  EXPECT: /# pass [1-9]\d*\r?\n# fail 0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=36e4ae876eee59feebd8341b7965c990f5e7d5bc1db11a237d304fa45ce8e817; exit=0; EXPECT=matched; output-sha256=65288058879f3e14d3c0fb4ce81b0d76d6bc25f0157ed843e0fc2958dbd67995; output-bytes=1438; shell=/bin/sh; cwd=/home/claude/kaizen-btp; path=fce7924b079f/15 entries

- [x] G3: two tenants subscribed through the MTX sidecar cannot read each other's kaizens
  CHECK: node test/verify-tenants.mjs
  EXPECT: tenant isolation verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=ee9049619f99f39cbd18ccdc08e5651790df568a68fbd03549dafa19ba90add6; exit=0; EXPECT=matched; output-sha256=0cadbed98db98bbfb7dfe738145c5a9127d4a7470f9ada6bbd99d52268eeec48; output-bytes=26; shell=/bin/sh; cwd=/home/claude/kaizen-btp; path=fce7924b079f/15 entries

- [x] G4: Harsha runs npm test and the isolation script on their own PC
  EVIDENCE: 2026-09-26, Windows 11, Node 22.19.0: `node --test` -> # pass 7 / # fail 0; `node test/verify-tenants.mjs` -> tenant isolation verified (exit 0). Re-run same day on Node 24.21.0: # pass 7 / # fail 0.

# Gates: phase 2, shop-floor capture app

OWNS: app/capture/**, test/verify-capture.mjs, srv/kaizen-service.js (retry-safe create), test/workflow.test.js

Scope: installable PWA at `/capture/` (plain HTML/JS served by CAP, no UI5 bootstrap, so it loads fast and fully offline): QR scan (native BarcodeDetector, jsQR fallback, `?eq=` label links), Before photo on a new kaizen or After photo on a started one, shrunk on the phone, voice-to-text, IndexedDB offline queue that syncs exactly once.

Note: the plan said "Lighthouse PWA check", but Lighthouse 12 removed its PWA category. G2 checks the same installability criteria directly (manifest with standalone display, start_url, 192/512 icons; service worker controlling the page; app opens offline).

- [x] G1: server accepts phone-generated IDs, answers a retried kaizen or photo with 409 (no duplicates), photo bytes round-trip
  CHECK: node --test --test-reporter=tap test/workflow.test.js
  EXPECT: /# pass [1-9]\d*\r?\n# fail 0/
  EVIDENCE: 2026-09-26, Windows 11, Node 24.21.0: # pass 7 / # fail 0

- [x] G2: in a real browser the app is installable, opens offline, queues a kaizen with a photo while offline, syncs it exactly once with the photo when back online, and attaches an After photo to a started kaizen
  CHECK: node test/verify-capture.mjs
  EXPECT: offline capture verified
  EVIDENCE: 2026-09-26, Edge (Playwright 1.63), Node 24.21.0: offline capture verified (exit 0). Negative controls: without service worker -> "service worker did not activate" (exit 1); with photo upload skipped -> "Before photo missing" (exit 1); After photo saved as Before -> "expected Before + After photos" (exit 1).

- [ ] G3: Harsha submits a kaizen on a real phone in under 60 s, including one in airplane mode that syncs after reconnecting
  EVIDENCE (partial): 2026-09-26, Android, Chrome, via HTTPS tunnel: online submit -> KAI-2026-0001 sent; airplane mode -> "Offline · 1 waiting" -> synced after reconnect (server log: POST received). The sync was rejected because no machine was given -> fixed (a470117: known machine required before queueing, covered in verify-capture.mjs). Still to check on the phone: under-60-s timing, voice input, QR scan.

# Gates: phase 3, approvals, management and security

OWNS: srv/manage-service.*, srv/kaizen-service.js, db/schema.cds, app/annotations.cds, app/kaizens/**, app/kpis/**, app/index.html, test/manage.test.js, test/verify-manage.mjs, package.json (auth)

Scope: Fiori Elements "Manage Kaizens" (inbox tab "Waiting for me" + "All kaizens", object page with workflow buttons shown only when allowed, draft editing of text, tasks and benefits, photos, history), "Kaizen KPIs" (count, open/closed, avg cycle days, verified EUR by plant x pillar), start page. UI5 pinned to 1.136.22 (long-term maintenance to Q4/2032).
Security: separate ManageService for managers only; photos only on own kaizens and only images up to 5 MB, replaced only by the uploader; tasks/benefits written by managers only (task owner may tick off own task); operators edit only until the first approval; only CI/plant managers verify, change or remove a verified benefit, also through drafts and deep writes; workflow fields only move through actions (stale drafts cannot roll back a status); kaizens cannot be deleted from the UI; mocked users only in [development], XSUAA in [production].

- [x] G1: security and manager-service rules hold at the API
  CHECK: node --test --test-reporter=tap test/manage.test.js
  EXPECT: /# pass [1-9]\d*\r?\n# fail 0/
  EVIDENCE: 2026-09-26, Node 24.21.0: # pass 9 / # fail 0. Negative controls (each rule removed once): workflow-field guard -> "a stale draft cannot roll back" fails; deep benefit guard -> "only CI managers verify benefits" fails; photo ownership -> "operators cannot touch..." fails; inbox filter -> "inbox follows the approval route" fails; operator edit limit -> "operators edit only until the first approval" fails.

- [x] G2: the Maria scenario is clickable end to end in the real apps (phone app -> Fiori inbox/approve/start/edit -> gate blocks -> After photo -> close -> KPI page)
  CHECK: node test/verify-manage.mjs
  EXPECT: maria scenario clickable
  EVIDENCE: 2026-09-26, Edge (Playwright 1.63), UI5 1.136.22, Node 24.21.0: maria scenario clickable (exit 0). Negative controls: KPI ignores savings -> "KPI row does not show the verified saving" (exit 1); verification gate removed -> gate message never appears (exit 1).

- [x] G3: the same manager flows work against real HANA Cloud (hybrid)
  CHECK: cds deploy --to hana --profile hybrid, then Fiori-style inbox query, draft edit + activate, KPIs through ManageService
  EVIDENCE: 2026-09-26, kaizen-hana (trial, us10): deploy "Make succeeded (21 files)"; inbox query with draft filter returns the new kaizen with canApprove=true for sam; draft edit + activate persisted; KPI total row returned.

- [x] G4: Harsha clicks through the demo script as maria / sam / klaus / petra on their PC
  EVIDENCE: 2026-09-26, Windows, Chrome + Edge, npm run watch: steps 1-8 of the walkthrough confirmed by Harsha. The verification gate blocked closing once because Verified by CI was not ticked (server data: benefit saved with verified=false); after ticking it the kaizen closed. Phone click-through of the manager app not done (optional).

# Gates: phase 4, AI assist

OWNS: srv/ai/**, test/ai.test.js, app/a3/**, AI parts of srv/*.js, srv/*.cds, app/capture/*, app/annotations.cds

Scope: provider interface (srv/ai/index.js) with 'stub' (default: free, offline, deterministic) and 'aicore' (SAP AI Core generative AI hub, orchestration REST, OAuth client credentials). Features: draft from photo (phone, online only), 5-Why analysis and A3 report (Fiori buttons, printable A3 page with Before/After photos), duplicate check (word overlap, no AI: warning on the phone, "possible duplicate" stored for approvers).
Security: AI output is untrusted (cut to size, pillar codes and booleans validated, A3 limited to 7 known sections); AI fields writable only by actions; per-user AI budget (30 calls / 10 min, 429); only JPEG/PNG sent to AI (415), max ~1 MB; photos shrunk to 768 px on the phone.

- [x] G1: stub and AI Core adapter pass the same contract tests; features and safety rules hold at the API
  CHECK: node --test --test-reporter=tap test/ai.test.js
  EXPECT: /# pass [1-9]\d*\r?\n# fail 0/
  EVIDENCE: 2026-09-26, Node 24.21.0: # pass 10 / # fail 0 (contract x2: stub, aicore against a local imitation of AI Core OAuth + /completion). Negative controls: trusting the AI's pillar code -> aicore contract fails; no AI budget -> "budgeted per user" fails; no image check -> "only for real images" fails.

- [x] G2: the AI steps are part of the clickable Maria scenario (draft with AI on the phone, 5-Why in Fiori, A3 report with both photos and the verified saving)
  CHECK: node test/verify-manage.mjs
  EXPECT: maria scenario clickable
  EVIDENCE: 2026-09-26, Edge, UI5 1.136.22: maria scenario clickable (exit 0).

- [x] G3: duplicate flag, 5-Why and A3 work on HANA Cloud (hybrid)
  EVIDENCE: 2026-09-26, kaizen-hana: deploy "Make succeeded (4 files)"; duplicate flagged (similarity 1.00), 5-Why stored, A3 with 7 sections stored.

- [ ] G4: the same contract tests pass against the real SAP AI Core (needs an AI Core instance with an orchestration deployment)
  CHECK: set AICORE_SERVICE_KEY (service key JSON) and AICORE_DEPLOYMENT_URL, then node --test --test-reporter=tap test/ai.test.js (adds "AI contract: aicore (real SAP AI Core)")
  EVIDENCE: pending: the BTP trial has no generative AI hub; runs when a paid/partner AI Core is available

# Gates: phase 5, S/4HANA integration

OWNS: srv/s4.js, srv/external/**, test/s4.test.js, test/verify-s4-sandbox.mjs, S/4 parts of srv/*.js, package.json (requires, kaizen)

Scope: API_EQUIPMENT (read) and API_MAINTNOTIFICATION (write) as OData V2 remote services. Locally mocked (srv/external + data, `--with-mocks`); on BTP through the subscriber's destination `S4HANA` ([production] credentials). Unknown machines are looked up in S/4 on scan or on create and cached in the tenant's tables (offline-scannable); plant managers sync a whole plant (`syncEquipment`); `kaizen.pmWriteBack` (default off) creates a maintenance notification when a kaizen starts, and a failing S/4 never blocks the kaizen (history note instead). S/4 definitions create no HANA tables.

- [x] G1: S/4 lookup, caching, plant sync (role-restricted), PM write-back toggle and S/4 outage handling
  CHECK: node --test --test-reporter=tap test/s4.test.js
  EXPECT: /# pass [1-9]\d*\r?\n# fail 0/
  EVIDENCE: 2026-09-26, Node 24.21.0: # pass 5 / # fail 0. Negative controls: no caching -> "cached for offline use" fails; toggle ignored -> "PM write-back ... (toggle)" fails.

- [x] G2: the phone app resolves a machine that is only in S/4 (browser, S/4 mocked)
  CHECK: node test/verify-capture.mjs
  EXPECT: offline capture verified
  EVIDENCE: 2026-09-26, Edge: offline capture verified (includes typing 10000045 -> "Press Line 5 Hydraulic Unit").

- [ ] G3: QR scan resolves LIVE equipment from the SAP Business Accelerator Hub sandbox
  CHECK: SAP_API_KEY=<key from api.sap.com> node test/verify-s4-sandbox.mjs
  EXPECT: live S/4 equipment resolved
  EVIDENCE: pending: needs Harsha's free API key (without it the script prints "skipped")
