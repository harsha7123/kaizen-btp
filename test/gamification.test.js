import cds from '@sap/cds'
import { test } from 'node:test'
import assert from 'node:assert/strict'

const { GET, POST } = cds.test(import.meta.dirname + '/..')
const as = username => ({ auth: { username, password: '' } })
const K = '/odata/v4/kaizen', M = '/odata/v4/manage'
const status = async (promise, code) => {
  const err = await promise.then(() => null, e => e)
  assert.ok(err, `expected HTTP ${code} but request succeeded`)
  assert.equal(err.response?.status ?? err.status, code, err.message)
}
const act = (id, action, data, user) => POST(`${M}/Kaizens(ID=${id},IsActiveEntity=true)/ManageService.${action}`, data ?? {}, as(user)).then(r => r.data)
const score = user => GET(`${K}/myScore()`, as(user)).then(r => r.data)
let closed // maria's closed safety kaizen, shared by the tests below (they run in order)

test('points and badges follow the kaizen through its life', async () => {
  const k = (await POST(`${K}/Kaizens`, { title: 'Guard missing on coupling', pillar_code: 'SHE', equipment_ID: 'P-1042', isSafety: true }, as('maria'))).data
  let s = await score('maria')
  assert.equal(s.points, 20, 'submitted 10 + safety 10')
  assert.deepEqual(s.badges, ['🌱 First kaizen', '🦺 Safety eye'])

  await act(k.ID, 'approve', {}, 'sam'); await act(k.ID, 'approve', {}, 'klaus'); await act(k.ID, 'approve', {}, 'eva') // safety: EHS step
  await act(k.ID, 'start', { owner: 'klaus' }, 'klaus')
  await POST(`${K}/Photos`, { kaizen_ID: k.ID, kind: 'After', mediaType: 'image/jpeg' }, as('klaus'))
  await POST(`${K}/Benefits`, { kaizen_ID: k.ID, type: 'Safety', annualSaving: 14200, verified: true }, as('klaus'))
  await act(k.ID, 'requestVerification', {}, 'klaus')
  await act(k.ID, 'close', {}, 'klaus')
  closed = k

  s = await score('maria')
  assert.equal(s.points, 20 + 10 + 30 + 14, 'approved 10 + closed 30 + 14 per verified 1,000 EUR')
  assert.ok(s.badges.includes('💶 10k saver'))
  assert.equal((await score('klaus')).points, 20, 'owner of a closed kaizen gets 20')
})

test('rank in my plant and the top 3', async () => {
  await POST(`${K}/Kaizens`, { title: 'Label faded on valve', pillar_code: 'AM', equipment_ID: 'C-1100' }, as('sam'))
  const s = await score('maria')
  assert.equal(s.plantName, 'Plant Hamburg')
  assert.equal(s.rank, 1)
  assert.ok(s.outOf >= 2)
  assert.equal(s.top[0].name, 'maria')
  assert.equal((await score('petra')).points, 0, 'no kaizens, no points, no error')
})

test('leaderboard for managers only', async () => {
  const rows = (await GET(`${M}/Leaderboard`, as('petra'))).data.value
  assert.equal(rows[0].name, 'maria')
  assert.equal(rows[0].rank, 1)
  assert.ok(rows[0].badges.includes('Safety eye'))
  for (let i = 1; i < rows.length; i++) assert.ok(rows[i - 1].points >= rows[i].points, 'sorted by points')
  await status(GET(`${M}/Leaderboard`, as('maria')), 403)
})

test('horizontal deployment: a closed kaizen is copied to another machine and credits its author', async () => {
  const src = (await GET(`${M}/Kaizens(ID=${closed.ID},IsActiveEntity=true)`, as('klaus'))).data
  assert.equal((await GET(`${M}/Kaizens(ID=${closed.ID},IsActiveEntity=true)?$select=ID,canDeploy`, as('klaus'))).data.canDeploy, true)

  await act(closed.ID, 'deployTo', { equipment_ID: 'P-2042' }, 'klaus')
  const copies = (await GET(`${K}/Kaizens?$filter=origin_ID eq ${closed.ID}`, as('klaus'))).data.value
  assert.equal(copies.length, 1)
  const c = copies[0]
  assert.equal(c.equipment_ID, 'P-2042'); assert.equal(c.plant_ID, 'DE_MUC')
  assert.equal(c.status_code, 'Submitted', 'the copy goes through approval on the new machine')
  assert.equal(c.similarTo_ID, null, 'not flagged as a duplicate of its origin')
  assert.match(c.problem, new RegExp(`^Horizontal deployment of ${src.number}`))
  const s = await score('maria')
  assert.ok(s.badges.includes('🎓 Teacher'), 'author gets the Teacher badge')
  const klaus = await score('klaus')
  assert.equal(klaus.points, 20, 'copying earns the copier nothing (still 20 as owner of the closed kaizen)')
  assert.ok(!klaus.badges.includes('🌱 First kaizen'), 'a copy is not a first kaizen')

  const open = (await POST(`${K}/Kaizens`, { title: 'Open one', pillar_code: 'AM', equipment_ID: 'P-1042' }, as('maria'))).data
  await status(act(open.ID, 'deployTo', { equipment_ID: 'P-2042' }, 'klaus'), 409)
  await status(act(closed.ID, 'deployTo', { equipment_ID: src.equipment_ID }, 'klaus'), 400)
  await status(act(closed.ID, 'deployTo', { equipment_ID: 'NOPE-9' }, 'klaus'), 400)
  await status(act(closed.ID, 'deployTo', { equipment_ID: 'P-3042' }, 'sam'), 403)
  const forged = (await POST(`${K}/Kaizens`, { title: 'Forged', pillar_code: 'AM', equipment_ID: 'P-1042', origin_ID: closed.ID }, as('maria'))).data
  assert.equal(forged.origin_ID, null, 'clients cannot claim a deployment (points)')
})

test('history shows status names', async () => {
  const h = (await GET(`${M}/StatusHistory?$filter=kaizen_ID eq ${closed.ID}&$expand=toState($select=name)&$orderby=createdAt`, as('sam'))).data.value
  assert.ok(h.some(x => x.toState?.name === 'Under Review'))
})
