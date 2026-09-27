# Kaizen on SAP BTP: handover

Status as of 2026-09-27. Read this first, then `PLAN.md` (phases and architecture), `GATES.md` (what was verified, with evidence),
`DEPLOY.md` (BTP runbook) and `readme.md` (local commands).

## What the product is

Operators report improvement ideas (kaizens) at the machine from their phone in under a minute, also offline. Supervisors, CI managers,
plant managers and EHS approve and track them in SAP Fiori. A kaizen can only be closed with proof: After photo, a benefit verified by a
CI manager, and all tasks done. Multi-tenant SaaS on SAP BTP: every customer subscribes and gets its own HANA database.

Demo story (the "Maria scenario"): Maria reports an oil leak on pump P-1042 with a photo -> Sam (supervisor) and Klaus (CI manager) approve
-> Klaus starts, runs a 5-Why, plans a task and a verified OEE benefit (78.2% -> 83.5%, EUR 14,200/yr) -> closing is blocked by the
verification gate -> After photo, task done -> closed -> A3 report -> KPIs show EUR 14,200 -> leaderboard.

## Status

| # | Phase | Status |
|---|---|---|
| 0 | Accounts and tools | done |
| 1 | Core backend (workflow, routing, roles, audit, multi-tenant) | done |
| 2 | Phone app `/capture/` (QR, photo, voice, offline queue) | built; open: real-phone check of 60 s timing, voice, QR scan |
| 3 | Fiori apps (inbox, approvals, drafts, KPIs) + security | done |
| 4 | AI assist (draft from photo, 5-Why, A3, duplicates) | done with the stand-in AI; SAP AI Core adapter built but not tested against a real AI Core (trial has none) |
| 5 | S/4HANA (equipment lookup and sync, PM write-back) | done, incl. live SAP sandbox |
| 6 | Deploy to BTP trial | deployed and live; open: customer 2 subaccount to demo isolation |
| 7 | Gamification (points, badges, leaderboard), horizontal deployment | done |
| - | UI: "white glass" design on all pages | done and deployed |

Automated checks: `npm test` runs 40 tests: API rules and security, offline phone app in a real browser, tenant isolation with the MTX
sidecar, the full demo clicked through the Fiori apps, and (if a key is configured) the live S/4 sandbox. All pass.

## Where things are

| Thing | Where |
|---|---|
| Code | https://github.com/harsha7123/kaizen-btp (branch `main`) |
| Live demo (customer 1) | https://4143d2d3trial-4143d2d3trial-dev-kaizen-btp.cfapps.us10-001.hana.ondemand.com |
| BTP trial | https://account.hanatrial.ondemand.com, region us10, org `4143d2d3trial`, space `dev`, subaccount `trial` |
| HANA Cloud | instance `kaizen-hana` (free tier) in HANA Cloud Central, mapped to the CF org/space |
| Data model | `db/schema.cds`, seed data `db/data/*.csv` |
| Business rules | `srv/kaizen-service.js` (shared), `srv/manage-service.js` (Fiori backend) |
| AI | `srv/ai/` (`stub.js` default, `aicore.js` for SAP AI Core) |
| S/4HANA | `srv/s4.js`, API definitions and mock data in `srv/external/` |
| Points and badges | `srv/score.js` |
| Phone app | `app/capture/` (`theme.css` = shared design tokens) |
| Fiori apps | `app/kaizens`, `app/kpis`, `app/leaderboard`, annotations in `app/annotations.cds`, glass layer `app/fiori-glass.css` |
| Deployment | `mta.yaml`, `xs-security.json` (6 roles), `.deploy/app-router/xs-app.json` |

## Set up a new developer PC (about 30 min)

1. Install Node 24 (nodejs.org), Git, Microsoft Edge (for the browser tests).
2. `npm i -g @sap/cds-dk mbt`, Cloud Foundry CLI v8 (github.com/cloudfoundry/cli/releases), then `cf install-plugin multiapps -f`,
   and on Windows `winget install ezwinports.make`. Add `C:\Program Files\Cloud Foundry` to PATH if `cf` is not found.
3. `git clone https://github.com/harsha7123/kaizen-btp.git`, then in the folder: `npm install` and `cd mtx/sidecar && npm install`.
4. `npm test` -> 40 pass. `npm run watch` -> http://localhost:4004, users maria / sam / klaus / petra / eva / admin with an empty password.

Not in git on purpose (each developer creates their own): `.cdsrc-private.json` with
- the HANA binding for hybrid testing: `cds bind -2 kaizen-smoke-db` (needs `cf login`), then `npm run hybrid`;
- the SAP API Hub sandbox key (own free key from api.sap.com), see readme "S/4HANA".

## Access the owner (Harsha) must grant

1. **GitHub:** repository Settings -> Collaborators -> Add people.
2. **BTP trial** (trials are personal; for a team use an enterprise / pay-as-you-go account, see "Next steps"):
   - global account -> Users -> add their e-mail with role collection **Global Account Administrator** (or Viewer);
   - subaccount `trial` -> Security -> Users -> their e-mail -> **Subaccount Administrator**;
   - subaccount `trial` -> Cloud Foundry -> Spaces -> `dev` -> Members -> add as **Space Developer** (needed for `cf deploy`);
   - for using the app: also assign the **Kaizen …** role collections.
3. **HANA Cloud Central:** subaccount role **SAP HANA Cloud Administrator** if they must start/stop `kaizen-hana`.
Never share passwords or the API key; each person logs in with their own SAP account.

## Run the demo on BTP

1. Start `kaizen-hana` (HANA Cloud Central -> ... -> Start). It stops every night; the apps crash without it.
2. `cf login -a https://api.cf.us10-001.hana.ondemand.com`, then `cf apps`. If they are stopped (the trial stops apps too; the URL then says
   "route does not exist"): `cf start kaizen-btp-mtx`, `cf start kaizen-btp-srv`, `cf start kaizen-btp`.
3. Open the live link, log in. Demo users need their own SAP accounts added under subaccount -> Security -> Users with one Kaizen role each.
4. Ship code or UI changes: `mbt build`, then `cf deploy mta_archives/kaizen-btp_1.0.0.mtar -f` (routes, roles and tenant data survive).
   After model changes also test locally on HANA: `cds deploy --to hana --profile hybrid`.

## Known limits and open items

| Item | Notes |
|---|---|
| Trial limits | HANA stops nightly, apps get stopped, trial expires unless extended: fine for demos, not for customer data |
| Customer 2 | create a 2nd subaccount, subscribe to "Kaizen", `cf map-route kaizen-btp cfapps.us10-001.hana.ondemand.com --hostname <subdomain>-4143d2d3trial-dev-kaizen-btp`, assign roles (DEPLOY.md step 3) |
| Real AI | stand-in AI cannot see photos; switch `cds.requires.ai.kind` to `aicore` with an SAP AI Core orchestration deployment (readme "AI assist"), then run `test/ai.test.js` with `AICORE_SERVICE_KEY` |
| iPhone home-screen app | login can fail with a cookie/state mismatch when opened from the home-screen icon; use the Safari tab for demos |
| Owner field | typed as a user ID (e-mail); a user picker needs the customer's IdP (e.g. SAP Cloud Identity / Azure AD) |
| Static files | web app files (no data) are reachable on the srv route without login; every data call needs login. Hardening: serve UI from the HTML5 repository or add auth to static routes |
| CSP header | not set (UI5 from the CDN and inline scripts); add a Content-Security-Policy when moving UI5 into the app |
| Photos | stored in HANA for the demo; move to SAP Object Store for volume (one place: photo upload/read) |

## Next steps (product track)

1. Enterprise / pay-as-you-go BTP account with separate dev / test / prod subaccounts, CI/CD running `npm test`.
2. Customer onboarding: subscribe, destination `S4HANA` to their S/4 (Cloud Connector for on-premise), map roles to their IdP groups.
3. SAP AI Core for real photo understanding, Object Store for photos, Event Mesh / Job Scheduling as needed (PLAN.md "Product track").
4. Security review (XSUAA scopes, CSP, data masking before AI), then optional SAP Store listing.
