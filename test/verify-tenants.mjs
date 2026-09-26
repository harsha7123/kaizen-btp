// Gate script: proves per-tenant data isolation with the real MTX sidecar (local SQLite per tenant).
// Starts sidecar + app, subscribes t1 and t2, writes as t1, reads as t1 (positive control) and t2 (must be empty).
import { spawn } from 'node:child_process'
import { rmSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

const root = join(import.meta.dirname, '..')
const SIDECAR = 4105, APP = 4104
const serve = (cwd, port, env = {}) => spawn(process.execPath, [join(cwd, 'node_modules/@sap/cds/bin/serve.js')], {
  cwd, env: { ...process.env, PORT: String(port), NODE_ENV: 'development', ...env }, stdio: ['ignore', 'ignore', 'pipe']
})
const clean = () => readdirSync(root).filter(f => /^db-t\d+\.sqlite/.test(f)).forEach(f => rmSync(join(root, f), { force: true }))
const auth = u => ({ authorization: 'Basic ' + Buffer.from(u + ':').toString('base64'), 'content-type': 'application/json' })
const up = async port => {
  for (let i = 0; i < 60; i++) {
    try { await fetch(`http://localhost:${port}/`); return } catch { await new Promise(r => setTimeout(r, 500)) }
  }
  throw new Error(`server on ${port} did not start`)
}
const call = async (method, url, user, body) => {
  const res = await fetch(url, { method, headers: auth(user), body: body && JSON.stringify(body) })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${url} as ${user} -> ${res.status} ${text.slice(0, 300)}`)
  return res.headers.get('content-type')?.includes('json') ? JSON.parse(text) : text
}

clean()
const procs = [serve(join(root, 'mtx/sidecar'), SIDECAR)]
let failed = false
try {
  await up(SIDECAR)
  procs.push(serve(root, APP, { CDS_ENV: 'with-mtx' }))
  await up(APP)
  for (const t of ['t1', 't2']) await call('PUT', `http://localhost:${SIDECAR}/-/cds/saas-provisioning/tenant/${t}`, 'yves', {})

  const K = `http://localhost:${APP}/odata/v4/kaizen/Kaizens`
  const k1 = await call('POST', K, 'maria', { title: 'Tenant 1 oil leak', pillar_code: 'AM', equipment_ID: 'P-1042' })
  const k2 = await call('POST', K, 'erin', { title: 'Tenant 2 conveyor jam', pillar_code: 'FI', equipment_ID: 'C-1100' })

  const seenBy1 = (await call('GET', K, 'maria')).value.map(k => k.ID)
  const seenBy2 = (await call('GET', K, 'erin')).value.map(k => k.ID)
  const check = (ok, msg) => { if (!ok) throw new Error(msg) }
  check(seenBy1.includes(k1.ID), 'positive control: t1 cannot read its own kaizen')
  check(seenBy2.includes(k2.ID), 'positive control: t2 cannot read its own kaizen')
  check(!seenBy1.includes(k2.ID), 'LEAK: t1 can read t2 data')
  check(!seenBy2.includes(k1.ID), 'LEAK: t2 can read t1 data')
  check(k1.number === k2.number, `numbering is not per tenant (${k1.number} vs ${k2.number})`)
  const direct = await fetch(`${K}(${k1.ID})`, { headers: auth('erin') })
  check(direct.status === 404, `t2 fetching t1 key directly returned ${direct.status}, expected 404`)
  console.log('tenant isolation verified')
} catch (e) {
  failed = true
  console.error(e.message)
} finally {
  procs.forEach(p => p.kill())
  await new Promise(r => setTimeout(r, 500))
  clean()
  process.exit(failed ? 1 : 0)
}
