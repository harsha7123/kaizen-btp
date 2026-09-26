# kaizen-btp

Multi-tenant TPM / Kaizen capture and reporting app on SAP BTP (CAP Node.js).

- Plan and roadmap: [PLAN.md](PLAN.md)
- Current phase acceptance gates: [GATES.md](GATES.md)

```
npm install && (cd mtx/sidecar && npm install)
npm test                       # everything: API, security, offline app, tenant isolation, clickable demo (~30 s)
node test/verify-tenants.mjs   # tenant isolation proof (MTX sidecar, t1/t2)
npm run watch                  # dev server http://localhost:4004 (users: maria, sam, klaus, petra, eva, admin)
                               # shop-floor app: http://localhost:4004/capture/  (QR labels can link to /capture/?eq=P-1042)
                               # start page:     http://localhost:4004/           (links to all apps)
                               # manager app:    http://localhost:4004/kaizens/index.html  (sam, klaus, petra, eva)
                               # KPIs:           http://localhost:4004/kpis/index.html
                               # photo viewer:   http://localhost:4004/photos/   (all kaizens with their photos)
node test/verify-capture.mjs   # offline capture gate (real browser: Edge on Windows)
node test/verify-manage.mjs    # Maria scenario clicked through the Fiori apps (needs internet for SAPUI5)
npm run hybrid                 # same, against HANA Cloud (start kaizen-hana first; after model changes: cds deploy --to hana --profile hybrid)
```
