import cds from '@sap/cds'
import { test } from 'node:test'
import assert from 'node:assert/strict'

// S/4HANA APIs are mocked in-process (srv/external/*, data from srv/external/data)
const { GET, POST } = cds.test(import.meta.dirname + '/..', '--with-mocks')
const as = username => ({ auth: { username, password: '' } })
const K = '/odata/v4/kaizen', M = '/odata/v4/manage'
const status = async (promise, code) => {
  const err = await promise.then(() => null, e => e)
  assert.ok(err, `expected HTTP ${code} but request succeeded`)
  assert.equal(err.response?.status ?? err.status, code, err.message)
}
const act = (id, action, data, user) => POST(`${M}/Kaizens(ID=${id},IsActiveEntity=true)/ManageService.${action}`, data ?? {}, as(user)).then(r => r.data)
const startedKaizen = async equipment_ID => {
  const k = (await POST(`${K}/Kaizens`, { title: 'Hydraulic hose chafing', pillar_code: 'PM', equipment_ID }, as('maria'))).data
  await act(k.ID, 'approve', {}, 'sam')
  await act(k.ID, 'approve', {}, 'klaus')
  return k
}

test('phone scan resolves a machine from S/4 once, then it is cached for offline use', async () => {
  const e = (await GET(`${K}/Equipment('10000045')`, as('maria'))).data
  assert.equal(e.name, 'Press Line 5 Hydraulic Unit')
  assert.equal(e.plant_ID, '1010')
  assert.equal(e.workCenter_ID, '1010/PRESS-L5')
  const list = (await GET(`${K}/Equipment?$select=ID`, as('maria'))).data.value.map(x => x.ID)
  assert.ok(list.includes('10000045'), 'now part of the offline list')
  await status(GET(`${K}/Equipment('NOPE-1')`, as('maria')), 404)
})

test('a kaizen on an S/4 machine gets its plant from S/4', async () => {
  const k = (await POST(`${K}/Kaizens`, { title: 'Overspray on robot base', pillar_code: 'QM', equipment_ID: '10000102' }, as('maria'))).data
  assert.equal(k.plant_ID, '1010')
  assert.ok(k.route_ID, 'generic route applies to S/4 plants')
  await status(POST(`${K}/Kaizens`, { title: 'x', pillar_code: 'AM', equipment_ID: 'NOPE-2' }, as('maria')), 400)
})

test('plant managers sync a plant from S/4; others cannot', async () => {
  assert.equal((await POST(`${M}/syncEquipment`, { plant: '1710' }, as('petra'))).data.value, 1)
  assert.ok((await GET(`${K}/Equipment('20000007')`, as('maria'))).data.name.includes('IM-7'))
  await status(POST(`${M}/syncEquipment`, { plant: '1710' }, as('sam')), 403)
  await status(POST(`${M}/syncEquipment`, { plant: '1710' }, as('maria')), 403)
})

test('PM write-back: starting a kaizen creates an S/4 maintenance notification (toggle)', async () => {
  cds.env.kaizen.pmWriteBack = false
  const off = await startedKaizen('10000045')
  assert.equal((await act(off.ID, 'start', { owner: 'klaus' }, 'klaus')).pmNotification, null, 'toggle off: nothing written')

  cds.env.kaizen.pmWriteBack = true
  try {
    const k = await startedKaizen('10000046')
    const started = await act(k.ID, 'start', { owner: 'klaus' }, 'klaus')
    assert.match(started.pmNotification ?? '', /^\d+$/, 'notification number stored')
    const s4 = await cds.connect.to('API_MAINTNOTIFICATION')
    const n = await s4.run(SELECT.one.from('API_MAINTNOTIFICATION.MaintenanceNotification').where({ MaintenanceNotification: started.pmNotification }))
    assert.equal(n.TechnicalObject, '10000046')
    assert.equal(n.TechObjIsEquipOrFuncnlLoc, 'EAMS_EQUI')
    assert.ok(n.NotificationText.startsWith(k.number) && n.NotificationText.length <= 40)
  } finally { cds.env.kaizen.pmWriteBack = false }
})

test('S/4 down: the kaizen still starts and the history says why there is no notification', async () => {
  const s4 = await cds.connect.to('API_MAINTNOTIFICATION')
  const outage = req => req.reject(503, 'S/4HANA unavailable')
  s4.prepend(() => s4.before('CREATE', 'MaintenanceNotification', outage))
  cds.env.kaizen.pmWriteBack = true
  try {
    const k = await startedKaizen('10000045')
    const started = await act(k.ID, 'start', { owner: 'klaus' }, 'klaus')
    assert.equal(started.status_code, 'InProgress')
    assert.equal(started.pmNotification, null)
    const notes = (await GET(`${K}/StatusHistory?$filter=kaizen_ID eq ${k.ID}`, as('klaus'))).data.value.map(h => h.note ?? '')
    assert.ok(notes.some(n => n.includes('S/4 maintenance notification not created')), 'history explains the missing notification')
  } finally {
    cds.env.kaizen.pmWriteBack = false
    s4._handlers.before = s4._handlers.before.filter(h => h.handler !== outage)
  }
})
