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

## AI assist

Default provider is the free stand-in (`cds.requires.ai.kind: "stub"`). To use SAP AI Core (generative AI hub):

1. Create an AI Core instance with an orchestration deployment (resource group `default`).
2. Set in package.json under `cds.requires`:
   ```json
   "ai": { "kind": "aicore", "model": "gpt-4o", "resourceGroup": "default",
           "deploymentUrl": "<AI_API_URL>/v2/inference/deployments/<deployment id>" }
   ```
3. Bind the `aicore` service (on BTP), or locally set `AICORE_SERVICE_KEY` to the service key JSON.
4. Prove it: with `AICORE_SERVICE_KEY` and `AICORE_DEPLOYMENT_URL` set, `node --test test/ai.test.js` runs the same contract tests against the real service.
