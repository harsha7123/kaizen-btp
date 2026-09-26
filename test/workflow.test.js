import cds from '@sap/cds'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const { GET, POST, PATCH } = cds.test(import.meta.dirname + '/..')
const as = username => ({ auth: { username, password: '' } })
const K = '/odata/v4/kaizen/Kaizens'
const act = (id, action, data, user) => POST(`${K}(${id})/KaizenService.${action}`, data ?? {}, as(user))
const status = async (promise, code) => {
  const err = await promise.then(() => null, e => e)
  assert.ok(err, `expected HTTP ${code} but request succeeded`)
  assert.equal(err.response?.status ?? err.status, code, err.message)
}
const create = (data, user = 'maria') => POST(K, { title: 'Oil leak at pump', pillar_code: 'AM', ...data }, as(user)).then(r => r.data)

test('Maria scenario: capture -> approve -> implement -> verify -> close', async () => {
  const k = await create({ equipment_ID: 'P-1042', estimatedBenefit: 3000, problem: 'Leakage around seal' })
  assert.match(k.number, /^KAI-\d{4}-\d{4}$/)
  assert.equal(k.plant_ID, 'DE_HH', 'plant derived from scanned equipment')
  assert.equal(k.status_code, 'Submitted')
  assert.equal(k.route_ID, 'a1b2c3d4-0000-4000-8000-000000000001', 'Hamburg/AM/<5k route chosen')

  await status(act(k.ID, 'approve', {}, 'maria'), 403) // operator cannot approve
  await status(act(k.ID, 'approve', {}, 'klaus'), 403) // step 1 belongs to Supervisor
  assert.equal((await act(k.ID, 'approve', {}, 'sam')).data.status_code, 'InReview')
  assert.equal((await act(k.ID, 'approve', {}, 'klaus')).data.status_code, 'Approved')
  await status(act(k.ID, 'approve', {}, 'klaus'), 409) // already approved

  assert.equal((await act(k.ID, 'start', { owner: 'klaus', dueDate: '2026-10-10' }, 'klaus')).data.status_code, 'InProgress')

  await status(POST('/odata/v4/kaizen/Benefits', { kaizen_ID: k.ID, type: 'OEE', verified: true }, as('maria')), 403)
  const task = (await POST('/odata/v4/kaizen/Tasks', { kaizen_ID: k.ID, title: 'Replace seal on P-1042' }, as('klaus'))).data

  assert.equal((await act(k.ID, 'requestVerification', {}, 'klaus')).data.status_code, 'Verification')
  await status(act(k.ID, 'close', {}, 'klaus'), 409) // gate: no After photo, no verified benefit, open task

  await POST('/odata/v4/kaizen/Photos', { kaizen_ID: k.ID, kind: 'After' }, as('klaus'))
  await POST('/odata/v4/kaizen/Benefits', { kaizen_ID: k.ID, type: 'OEE', baseline: 78.2, improved: 83.5, unit: '% OEE', annualSaving: 14200, verified: true }, as('klaus'))
  await status(act(k.ID, 'close', {}, 'klaus'), 409) // task still open
  await PATCH(`/odata/v4/kaizen/Tasks(${task.ID})`, { done: true }, as('klaus'))

  assert.equal((await act(k.ID, 'close', {}, 'klaus')).data.status_code, 'Closed')
  await status(PATCH(`${K}(${k.ID})`, { title: 'changed' }, as('klaus')), 409)
  await status(POST('/odata/v4/kaizen/Photos', { kaizen_ID: k.ID, kind: 'After' }, as('klaus')), 409)

  const hist = (await GET(`/odata/v4/kaizen/StatusHistory?$filter=kaizen_ID eq ${k.ID}`, as('maria'))).data.value
  assert.equal(hist.length, 6, 'every transition is audited')
})

test('safety kaizens get a mandatory EHS step', async () => {
  const k = await create({ equipment_ID: 'P-1042', isSafety: true, estimatedBenefit: 100 })
  await act(k.ID, 'approve', {}, 'sam')
  assert.equal((await act(k.ID, 'approve', {}, 'klaus')).data.status_code, 'InReview')
  assert.equal((await act(k.ID, 'approve', {}, 'eva')).data.status_code, 'Approved')
})

test('high-value kaizens route to the plant manager', async () => {
  const k = await create({ equipment_ID: 'P-1042', estimatedBenefit: 20000 })
  assert.equal(k.route_ID, 'a1b2c3d4-0000-4000-8000-000000000003')
  await act(k.ID, 'approve', {}, 'sam')
  await act(k.ID, 'approve', {}, 'klaus')
  await status(act(k.ID, 'approve', {}, 'klaus'), 403)
  assert.equal((await act(k.ID, 'approve', {}, 'petra')).data.status_code, 'Approved')
})

test('rejection needs a reason and ends the kaizen', async () => {
  const k = await create({ equipment_ID: 'C-1100' })
  await status(act(k.ID, 'reject', {}, 'sam'), 400)
  assert.equal((await act(k.ID, 'reject', { note: 'Duplicate of KAI-0001' }, 'sam')).data.status_code, 'Rejected')
  await status(act(k.ID, 'approve', {}, 'sam'), 409)
})

test('operators edit only their own kaizens and cannot fake status', async () => {
  const mine = await create({ equipment_ID: 'P-1042' })
  const other = await create({ equipment_ID: 'P-1042' }, 'sam')
  await PATCH(`${K}(${mine.ID})`, { title: 'Better title', status_code: 'Closed' }, as('maria'))
  const after = (await GET(`${K}(${mine.ID})`, as('maria'))).data
  assert.equal(after.title, 'Better title')
  assert.equal(after.status_code, 'Submitted', 'status is read-only for clients')
  await status(PATCH(`${K}(${other.ID})`, { title: 'hijack' }, as('maria')), 403)
})

test('kaizen numbers are sequential', async () => {
  const a = await create({ equipment_ID: 'P-1042' }), b = await create({ equipment_ID: 'P-1042' })
  assert.equal(+b.number.slice(-4), +a.number.slice(-4) + 1)
})
