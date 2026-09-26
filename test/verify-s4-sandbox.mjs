// Gate script (Phase 5): a phone scan resolves LIVE equipment from the SAP Business Accelerator Hub sandbox.
// Needs a free API key from https://api.sap.com (Settings > Show API Key):  SAP_API_KEY=... node test/verify-s4-sandbox.mjs
// Without the key it reports "skipped" (exit 0) so the normal test run stays green; the gate only counts with the key.
import { spawn } from 'node:child_process'
import { join } from 'node:path'

const KEY = process.env.SAP_API_KEY
if (!KEY) { console.log('skipped: set SAP_API_KEY to run the S/4 sandbox gate'); process.exit(0) }

const root = join(import.meta.dirname, '..'), PORT = 4108, BASE = `http://localhost:${PORT}`
const SANDBOX = 'https://sandbox.api.sap.com/s4hanacloud/sap/opu/odata/sap/API_EQUIPMENT'
const check = (ok, msg) => { if (!ok) throw new Error(msg) }

// 1. pick a real machine straight from the sandbox
const direct = await fetch(`${SANDBOX}/Equipment?$top=1&$select=Equipment,EquipmentName,MaintenancePlant&$format=json`, { headers: { APIKey: KEY, accept: 'application/json' } })
if (!direct.ok) { console.error(`sandbox rejected the request: HTTP ${direct.status} (check the API key)`); process.exit(1) }
const live = (await direct.json()).d.results[0]
console.log(`  sandbox has ${live.Equipment} "${live.EquipmentName}" (plant ${live.MaintenancePlant})`)

// 2. start the app wired to the sandbox (no mock), then scan that machine as the phone would
const config = { requires: { API_EQUIPMENT: { kind: 'odata-v2', model: 'srv/external/API_EQUIPMENT', credentials: { url: SANDBOX, headers: { APIKey: KEY } } } } }
const server = spawn(process.execPath, [join(root, 'node_modules/@sap/cds/bin/serve.js'), 'all', '--in-memory'],
  { cwd: root, env: { ...process.env, PORT: String(PORT), NODE_ENV: 'development', CDS_CONFIG: JSON.stringify(config) }, stdio: ['ignore', 'ignore', 'pipe'] })
let failed = false
try {
  for (let i = 0; ; i++) { try { await fetch(BASE); break } catch { check(i < 60, 'server did not start'); await new Promise(r => setTimeout(r, 500)) } }
  const auth = { authorization: 'Basic ' + Buffer.from('maria:').toString('base64') }
  const res = await fetch(`${BASE}/odata/v4/kaizen/Equipment('${encodeURIComponent(live.Equipment)}')`, { headers: auth })
  check(res.ok, `app could not resolve ${live.Equipment}: HTTP ${res.status} ${await res.text()}`)
  const e = await res.json()
  check(e.name === live.EquipmentName && e.plant_ID === live.MaintenancePlant, `wrong machine data: ${JSON.stringify(e)}`)
  console.log(`  app resolved it: ${e.ID} "${e.name}", plant ${e.plant_ID}, work center ${e.workCenter_ID}`)
  console.log('live S/4 equipment resolved')
} catch (e) {
  failed = true
  console.error(e.message)
} finally {
  server.kill()
  process.exit(failed ? 1 : 0)
}
