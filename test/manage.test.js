import cds from '@sap/cds'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const { GET, POST, PATCH, PUT } = cds.test(import.meta.dirname + '/..')
const as = username => ({ auth: { username, password: '' } })
const K = '/odata/v4/kaizen', M = '/odata/v4/manage'
const status = async (promise, code) => {
  const err = await promise.then(() => null, e => e)
  assert.ok(err, `expected HTTP ${code} but request succeeded`)
  assert.equal(err.response?.status ?? err.status, code, err.message)
}
const capture = (data = {}, user = 'maria') => POST(`${K}/Kaizens`, { title: 'Oil leak', pillar_code: 'AM', equipment_ID: 'P-1042', ...data }, as(user)).then(r => r.data)
const act = (id, action, data, user) => POST(`${M}/Kaizens(ID=${id},IsActiveEntity=true)/ManageService.${action}`, data ?? {}, as(user)).then(r => r.data)
const inbox = user => GET(`${M}/Kaizens?$filter=waitingForMe eq true`, as(user)).then(r => r.data.value.map(k => k.ID))
const draft = {
  edit: (id, user) => POST(`${M}/Kaizens(ID=${id},IsActiveEntity=true)/ManageService.draftEdit`, { PreserveChanges: true }, as(user)),
  patch: (id, data, user) => PATCH(`${M}/Kaizens(ID=${id},IsActiveEntity=false)`, data, as(user)),
  addBenefit: (id, data, user) => POST(`${M}/Kaizens(ID=${id},IsActiveEntity=false)/benefits`, data, as(user)),
  activate: (id, user) => POST(`${M}/Kaizens(ID=${id},IsActiveEntity=false)/ManageService.draftActivate`, {}, as(user))
}

// ---- security: phone API ----
test('operators cannot touch other people\'s photos, tasks or benefits', async () => {
  const mine = await capture(), sams = await capture({}, 'sam')
  await status(POST(`${K}/Photos`, { kaizen_ID: sams.ID, kind: 'Before' }, as('maria')), 403)
  const klausPhoto = (await POST(`${K}/Photos`, { kaizen_ID: mine.ID, kind: 'Before', mediaType: 'image/jpeg' }, as('klaus'))).data
  await status(PUT(`${K}/Photos(${klausPhoto.ID})/content`, Buffer.from([0xff, 0xd8, 0xff, 0xd9]), { ...as('maria'), headers: { 'content-type': 'image/jpeg' } }), 403)
  await status(POST(`${K}/Tasks`, { kaizen_ID: mine.ID, title: 'mine' }, as('maria')), 403)
  await status(POST(`${K}/Benefits`, { kaizen_ID: mine.ID, type: 'Cost', annualSaving: 1 }, as('maria')), 403)
})

test('photos must be images', async () => {
  const k = await capture()
  await status(POST(`${K}/Photos`, { kaizen_ID: k.ID, kind: 'Before', mediaType: 'text/html' }, as('maria')), 415)
  const p = (await POST(`${K}/Photos`, { kaizen_ID: k.ID, kind: 'Before', mediaType: 'image/jpeg' }, as('maria'))).data
  await status(PUT(`${K}/Photos(${p.ID})/content`, '<script>alert(1)</script>', { ...as('maria'), headers: { 'content-type': 'text/html' } }), 415)
})

test('operators edit only until the first approval; task owners tick off their own tasks', async () => {
  const k = await capture()
  await act(k.ID, 'approve', {}, 'sam')
  await status(PATCH(`${K}/Kaizens(${k.ID})`, { title: 'late edit' }, as('maria')), 409)
  await act(k.ID, 'approve', {}, 'klaus')
  await act(k.ID, 'start', { owner: 'maria' }, 'klaus')
  const t = (await POST(`${K}/Tasks`, { kaizen_ID: k.ID, title: 'Replace seal', owner: 'maria' }, as('klaus'))).data
  const other = (await POST(`${K}/Tasks`, { kaizen_ID: k.ID, title: 'Order part', owner: 'sam' }, as('klaus'))).data
  await PATCH(`${K}/Tasks(${t.ID})`, { done: true }, as('maria'))
  await status(PATCH(`${K}/Tasks(${other.ID})`, { done: true }, as('maria')), 403)
})

// ---- manager service ----
test('manager app is closed to operators', async () => {
  await status(GET(`${M}/Kaizens`, as('maria')), 403)
  await status(GET(`${M}/Kaizens?$filter=waitingForMe eq true`, as('maria')), 403)
})

test('inbox follows the approval route and shows only allowed buttons', async () => {
  const k = await capture()
  assert.ok((await inbox('sam')).includes(k.ID), 'supervisor sees a new kaizen')
  assert.ok(!(await inbox('klaus')).includes(k.ID), 'CI manager does not see it before the supervisor approved')
  const cols = '$select=ID,canApprove,canStart,canRequestVerification,canClose'
  assert.equal((await GET(`${M}/Kaizens(ID=${k.ID},IsActiveEntity=true)?${cols}`, as('sam'))).data.canApprove, true)
  assert.equal((await GET(`${M}/Kaizens(ID=${k.ID},IsActiveEntity=true)?${cols}`, as('klaus'))).data.canApprove, false)

  await act(k.ID, 'approve', {}, 'sam')
  assert.ok(!(await inbox('sam')).includes(k.ID))
  assert.ok((await inbox('klaus')).includes(k.ID), 'CI manager sees it after the supervisor approved')
  await act(k.ID, 'approve', {}, 'klaus')
  assert.equal((await GET(`${M}/Kaizens(ID=${k.ID},IsActiveEntity=true)?${cols}`, as('klaus'))).data.canStart, true)
  await act(k.ID, 'start', { owner: 'sam' }, 'klaus')
  assert.ok((await inbox('sam')).includes(k.ID), 'owner sees the kaizen they must implement')
})

test('a stale draft cannot roll back the workflow', async () => {
  const k = await capture()
  await draft.edit(k.ID, 'klaus')
  await draft.patch(k.ID, { title: 'Oil leak at pump seal (edited)', status_code: 'Closed' }, 'klaus')
  await act(k.ID, 'approve', {}, 'sam') // approved while klaus still edits
  await draft.activate(k.ID, 'klaus')
  const now = (await GET(`${K}/Kaizens(${k.ID})`, as('klaus'))).data
  assert.equal(now.title, 'Oil leak at pump seal (edited)')
  assert.equal(now.status_code, 'InReview', 'approval made during the draft survives activation')
})

test('only CI managers verify benefits, also through drafts', async () => {
  const k = await capture()
  await draft.edit(k.ID, 'sam')
  await draft.addBenefit(k.ID, { type: 'OEE', annualSaving: 14200, verified: true }, 'sam')
  await status(draft.activate(k.ID, 'sam'), 403)
  const klaus = await capture()
  await draft.edit(klaus.ID, 'klaus')
  await draft.addBenefit(klaus.ID, { type: 'OEE', baseline: 78.2, improved: 83.5, annualSaving: 14200, verified: true }, 'klaus')
  await draft.activate(klaus.ID, 'klaus')
  // a supervisor editing the text later keeps the verified benefit intact, but may not change it
  await draft.edit(klaus.ID, 'sam')
  await draft.patch(klaus.ID, { problem: 'Seal worn' }, 'sam')
  await draft.activate(klaus.ID, 'sam')
  const b = (await GET(`${K}/Benefits?$filter=kaizen_ID eq ${klaus.ID}`, as('sam'))).data.value
  assert.equal(b.length, 1)
  assert.equal(b[0].verified, true)
  await status(PATCH(`${K}/Benefits(${b[0].ID})`, { annualSaving: 99999 }, as('sam')), 403)
})

test('new kaizens created in the Fiori app get a number and a route', async () => {
  const d = (await POST(`${M}/Kaizens`, { title: 'Office: duplicate data entry', pillar_code: 'OT', plant_ID: 'DE_MUC' }, as('klaus'))).data
  const k = (await draft.activate(d.ID, 'klaus')).data
  assert.match(k.number, /^KAI-\d{4}-\d{4}$/)
  assert.equal(k.status_code, 'Submitted')
  assert.equal(k.nextRole, 'Supervisor')
})

test('KPIs: verified savings and cycle time from closed kaizens', async () => {
  const k = await capture({ equipment_ID: 'P-2042' }) // Munich, so other tests do not disturb the numbers
  await act(k.ID, 'approve', {}, 'sam')
  await act(k.ID, 'approve', {}, 'klaus')
  await act(k.ID, 'start', { owner: 'klaus' }, 'klaus')
  await POST(`${K}/Photos`, { kaizen_ID: k.ID, kind: 'After', mediaType: 'image/jpeg' }, as('klaus'))
  await POST(`${K}/Benefits`, { kaizen_ID: k.ID, type: 'OEE', annualSaving: 14200, verified: true }, as('klaus'))
  await act(k.ID, 'requestVerification', {}, 'klaus')
  await act(k.ID, 'close', {}, 'klaus')
  const rows = (await GET(`${M}/Kpis`, as('petra'))).data.value
  const muc = rows.find(r => r.plant === 'DE_MUC' && r.pillar === 'AM')
  assert.equal(muc.closed, 1)
  assert.equal(Number(muc.verifiedSaving), 14200)
  assert.ok(muc.avgCycleDays !== null && muc.avgCycleDays >= 0)
  const total = rows.find(r => r.plant === 'ALL')
  assert.ok(total.kaizens >= muc.kaizens && Number(total.verifiedSaving) >= 14200)
})
