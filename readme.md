# kaizen-btp

Multi-tenant TPM / Kaizen capture and reporting app on SAP BTP (CAP Node.js).

- Plan and roadmap: [PLAN.md](PLAN.md)
- Current phase acceptance gates: [GATES.md](GATES.md)

```
npm install && (cd mtx/sidecar && npm install)
npm test                       # workflow tests
node test/verify-tenants.mjs   # tenant isolation proof (MTX sidecar, t1/t2)
npm run watch                  # dev server http://localhost:4004 (users: maria, sam, klaus, petra, eva, admin)
                               # shop-floor app: http://localhost:4004/capture/  (QR labels can link to /capture/?eq=P-1042)
                               # photo viewer:   http://localhost:4004/photos/   (all kaizens with their photos)
node test/verify-capture.mjs   # offline capture gate (real browser: Edge on Windows)
```
