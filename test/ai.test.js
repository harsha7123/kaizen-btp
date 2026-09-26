import cds from '@sap/cds'
import { test, describe, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { readFileSync } from 'node:fs'

const { GET, POST } = cds.test(import.meta.dirname + '/..')
const as = username => ({ auth: { username, password: '' } })
const K = '/odata/v4/kaizen', M = '/odata/v4/manage'
const status = async (promise, code) => {
  const err = await promise.then(() => null, e => e)
  assert.ok(err, `expected HTTP ${code} but request succeeded`)
  assert.equal(err.response?.status ?? err.status, code, err.message)
}
const photo = readFileSync(import.meta.dirname + '/../app/capture/icon-192.png').toString('base64')

// ---- a local imitation of SAP AI Core (OAuth + orchestration /completion), to test the real adapter offline ----
function fakeAICore () {
  const seen = []
  const answers = {
    draft: { title: 'Oil leak at pump shaft seal'.padEnd(200, '!'), problem: 'Oil drips from the seal.', pillar_code: 'XX', wasteType: 'Leak', isSafety: 'yes' },
    fiveWhy: { whys: Array.from({ length: 7 }, (_, i) => ({ question: `Why ${i + 1}?`, answer: `Because ${i + 1}` })), rootCause: 'No inspection standard for the seal' },
    a3: { background: 'B', currentCondition: 'C', goal: 'G', rootCause: 'R', countermeasures: 'M', results: 'Res', followUp: 'F', extra: 'ignored' }
  }
  const srv = createServer((req, res) => {
    let body = ''
    req.on('data', d => body += d).on('end', () => {
      if (req.url === '/oauth/token') return res.end(JSON.stringify({ access_token: 'tok', expires_in: 3600 }))
      const b = JSON.parse(body), system = b.orchestration_config.module_configurations.templating_module_config.template[0].content
      seen.push({ auth: req.headers.authorization, group: req.headers['ai-resource-group'], body: b })
      const kind = system.includes('whys') ? 'fiveWhy' : system.includes('A3') ? 'a3' : 'draft'
      res.setHeader('content-type', 'application/json')
      res.end(JSON.stringify({ orchestration_result: { choices: [{ message: { content: 'Sure:\n```json\n' + JSON.stringify(answers[kind]) + '\n```' } }] } }))
    })
  })
  return { srv, seen }
}

const facts = {
  number: 'KAI-2026-0001', title: 'Oil leak', problem: 'Oil drips', pillarName: 'Autonomous Maintenance', plantName: 'Plant Hamburg', machine: 'Hydraulic Pump P-1042',
  createdBy: 'maria', createdAt: '2026-09-26T10:00:00Z', isSafety: false, estimatedBenefit: 3000, status: 'Closed', nextRole: null, rootCause: null, fiveWhy: null,
  countermeasure: null, tasks: [{ title: 'Replace seal', owner: 'klaus', done: true }], benefits: [{ type: 'OEE', baseline: 78.2, improved: 83.5, unit: '%', annualSaving: 14200, verified: true }]
}

// ---- the same contract for every provider ----
const providers = [['stub', () => ({ kind: 'stub' })], ['aicore (local imitation)', null]]
if (process.env.AICORE_SERVICE_KEY && process.env.AICORE_DEPLOYMENT_URL)
  providers.push(['aicore (real SAP AI Core)', () => ({ kind: 'aicore', deploymentUrl: process.env.AICORE_DEPLOYMENT_URL, model: process.env.AICORE_MODEL ?? 'gpt-4o' })])

for (const [name, config] of providers) describe(`AI contract: ${name}`, () => {
  let fake, saved
  before(async () => {
    saved = { ai: cds.env.requires.ai, aicore: cds.env.requires.aicore }
    if (config) cds.env.requires.ai = config()
    else {
      fake = fakeAICore()
      await new Promise(r => fake.srv.listen(0, r))
      const url = `http://localhost:${fake.srv.address().port}`
      cds.env.requires.ai = { kind: 'aicore', deploymentUrl: url, resourceGroup: 'kaizen', model: 'gpt-4o' }
      cds.env.requires.aicore = { credentials: { url, clientid: 'id', clientsecret: 'secret' } }
    }
  })
  after(() => { cds.env.requires.ai = saved.ai; cds.env.requires.aicore = saved.aicore; fake?.srv.close() })
  const ai = () => import('../srv/ai/index.js')

  test('draft from photo: usable, cut to size, only allowed pillar codes', async () => {
    const d = await (await ai()).draftFromPhoto({ image: photo, machine: { name: 'Hydraulic Pump P-1042', plantName: 'Plant Hamburg', workCenter_ID: 'HH-L3' }, hint: 'oil leak', lang: 'en' })
    assert.ok(d.title && d.title.length <= 120, 'title present and <= 120 chars')
    assert.ok(d.problem && d.problem.length <= 2000)
    assert.ok(d.pillar_code === null || ['FI', 'AM', 'PM', 'QM', 'EEM', 'TE', 'SHE', 'OT'].includes(d.pillar_code))
    assert.equal(typeof d.isSafety, 'boolean')
    if (fake) {
      assert.equal(d.pillar_code, null, 'invented pillar code XX dropped')
      assert.equal(d.isSafety, false, 'non-boolean "yes" not trusted')
      const call = fake.seen.at(-1)
      assert.equal(call.auth, 'Bearer tok'); assert.equal(call.group, 'kaizen')
      assert.ok(JSON.stringify(call.body).includes('data:image/jpeg;base64,'), 'photo sent to the model')
    }
  })

  test('5-Why: at most 5 steps and a root cause', async () => {
    const r = await (await ai()).fiveWhy({ title: 'Oil leak at pump seal', problem: 'Oil drips every shift', machine: 'Hydraulic Pump P-1042' })
    assert.ok(r.whys.length >= 1 && r.whys.length <= 5)
    for (const w of r.whys) assert.ok(w.question && w.answer)
    assert.ok(r.rootCause)
  })

  test('A3: exactly the seven sections, nothing else', async () => {
    const r = await (await ai()).a3(facts)
    assert.deepEqual(Object.keys(r), ['background', 'currentCondition', 'goal', 'rootCause', 'countermeasures', 'results', 'followUp'])
    for (const v of Object.values(r)) assert.ok(typeof v === 'string' && v.length && v.length <= 3000)
  })
})

// ---- the features through the services (default provider: stub) ----
test('phone: draft from photo, only for real images', async () => {
  const d = (await POST(`${K}/draftFromPhoto`, { image: photo, equipment_ID: 'P-1042' }, as('maria'))).data
  assert.ok(d.title.includes('P-1042'), 'draft mentions the scanned machine')
  assert.equal(d.provider, 'stub')
  await status(POST(`${K}/draftFromPhoto`, { image: Buffer.from('<script>alert(1)</script>').toString('base64') }, as('maria')), 415)
})

test('phone: AI calls are budgeted per user', async () => {
  let blocked = 0
  for (let i = 0; i < 35; i++) await POST(`${K}/draftFromPhoto`, { equipment_ID: 'P-1042' }, as('eva')).catch(e => { if (e.response?.status === 429) blocked++ })
  assert.ok(blocked >= 5, `expected the budget to block calls, blocked ${blocked}`)
})

test('duplicates: warned on the phone and flagged for approvers', async () => {
  const first = (await POST(`${K}/Kaizens`, { title: 'Hydraulic oil leak at pump seal', problem: 'Oil dripping from shaft seal', pillar_code: 'AM', equipment_ID: 'P-3042' }, as('maria'))).data
  const hits = (await GET(`${K}/similar(title='Oil leak at the pump seal',problem='oil drips from the shaft seal',equipment_ID='P-3042')`, as('maria'))).data.value
  assert.equal(hits[0]?.ID, first.ID, 'phone check finds the earlier kaizen')
  const again = (await POST(`${K}/Kaizens`, { title: 'Oil leak at the pump seal', problem: 'oil drips from the shaft seal', pillar_code: 'AM', equipment_ID: 'P-3042' }, as('maria'))).data
  assert.equal(again.similarTo_ID, first.ID, 'stored as possible duplicate')
  assert.ok(Number(again.similarity) >= 0.5)
  const other = (await POST(`${K}/Kaizens`, { title: 'Conveyor belt misaligned', pillar_code: 'FI', equipment_ID: 'P-3042' }, as('maria'))).data
  assert.equal(other.similarTo_ID, null, 'unrelated kaizen is not flagged')
})

test('managers: 5-Why fills the analysis and root cause; A3 stores all sections; operators cannot', async () => {
  const k = (await POST(`${K}/Kaizens`, { title: 'Guard missing on coupling', pillar_code: 'SHE', equipment_ID: 'C-1100', isSafety: true }, as('maria'))).data
  const act = (a, u) => POST(`${M}/Kaizens(ID=${k.ID},IsActiveEntity=true)/ManageService.${a}`, {}, as(u))
  const after = (await act('fiveWhy', 'sam')).data
  assert.match(after.fiveWhy, /^1\. Why/)
  assert.match(after.fiveWhy, /Root cause: /)
  assert.ok(after.rootCause, 'empty root cause filled')
  const a3 = JSON.parse((await act('generateA3', 'klaus')).data.a3)
  assert.ok(a3.background.includes('Guard') || a3.background.includes(k.number))
  assert.ok(a3.followUp)
  await status(act('fiveWhy', 'maria'), 403)
  // AI fields cannot be written by clients
  await POST(`${K}/Kaizens`, { title: 'x', pillar_code: 'AM', equipment_ID: 'P-1042', fiveWhy: 'fake', a3: '{}' }, as('maria'))
    .then(r => { assert.equal(r.data.fiveWhy, null); assert.equal(r.data.a3, null) })
})
