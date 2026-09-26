# Gates: phase 1, core multi-tenant backend

OWNS: db/**, srv/**, test/**, package.json, PLAN.md, GATES.md, readme.md

Scope: CAP backend with the kaizen data model, workflow state machine, table-driven routing, verification gate, role restrictions and audit trail, proven isolated across two local tenants.

- [x] G1: the CDS model compiles to SQL with all kaizen tables
  CHECK: npx cds compile srv --to sql
  EXPECT: /^(?=[\s\S]*CREATE TABLE kaizen_Kaizens \()(?=[\s\S]*CREATE TABLE kaizen_Photos \()(?=[\s\S]*CREATE TABLE kaizen_Tasks \()(?=[\s\S]*CREATE TABLE kaizen_Benefits \()(?=[\s\S]*CREATE TABLE kaizen_StatusHistory \()(?=[\s\S]*CREATE TABLE kaizen_WorkflowRoutes \()/
  EVIDENCE: automatic-evidence=v1; definition-sha256=062b49597be538ff7109b87458c705fa1e6fc63d9d0b6911b615a0522386c95e; exit=0; EXPECT=matched; output-sha256=74bfaf0680d913673be5ee5c7d5588d7a897b729af6d43f3153109c971102126; output-bytes=7310; shell=/bin/sh; cwd=/home/claude/kaizen-btp; path=fce7924b079f/15 entries

- [x] G2: workflow, routing, verification gate and role tests all pass
  CHECK: node --test
  EXPECT: /# pass [1-9]\d*\r?\n# fail 0/
  EVIDENCE: automatic-evidence=v1; definition-sha256=36e4ae876eee59feebd8341b7965c990f5e7d5bc1db11a237d304fa45ce8e817; exit=0; EXPECT=matched; output-sha256=65288058879f3e14d3c0fb4ce81b0d76d6bc25f0157ed843e0fc2958dbd67995; output-bytes=1438; shell=/bin/sh; cwd=/home/claude/kaizen-btp; path=fce7924b079f/15 entries

- [x] G3: two tenants subscribed through the MTX sidecar cannot read each other's kaizens
  CHECK: node test/verify-tenants.mjs
  EXPECT: tenant isolation verified
  EVIDENCE: automatic-evidence=v1; definition-sha256=ee9049619f99f39cbd18ccdc08e5651790df568a68fbd03549dafa19ba90add6; exit=0; EXPECT=matched; output-sha256=0cadbed98db98bbfb7dfe738145c5a9127d4a7470f9ada6bbd99d52268eeec48; output-bytes=26; shell=/bin/sh; cwd=/home/claude/kaizen-btp; path=fce7924b079f/15 entries

- [x] G4: Harsha runs npm test and the isolation script on their own PC
  EVIDENCE: 2026-09-26, Windows 11, Node 22.19.0: `node --test` -> # pass 7 / # fail 0; `node test/verify-tenants.mjs` -> tenant isolation verified (exit 0). Upgrade to Node 24 before Phase 6 (sidecar engines: ^24).
