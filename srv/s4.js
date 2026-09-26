import cds from '@sap/cds'

// S/4HANA integration (Phase 5). Machines come from the customer's S/4 (API_EQUIPMENT) and are cached in the
// tenant's own tables, so the phone can scan them offline. Optional PM write-back creates a maintenance
// notification (API_MAINTNOTIFICATION) when a kaizen starts. Locally both APIs are mocked (cds watch / --with-mocks);
// on BTP they go through the subscriber's destination "S4HANA". Not connected = null, never an error for the user.
const LOG = cds.log('s4')

async function connect (name) {
  try { return await cds.connect.to(name) }
  catch (e) { if (/No credentials|not configured|Didn't find a configuration/i.test(e.message)) return null; throw e }
}

const toLocal = e => ({
  ID: e.Equipment, name: e.EquipmentName ?? e.Equipment, functionalLocation: e.FunctionalLocation,
  plant_ID: e.MaintenancePlant, workCenter_ID: e.MainWorkCenter ? `${e.MainWorkCenterPlant ?? e.MaintenancePlant}/${e.MainWorkCenter}` : null,
  workCenterName: e.MainWorkCenter
})

// cache S/4 equipment (plus any unknown plant / work center) in the tenant's tables
async function cache (rows) {
  const { Equipment, Plants, WorkCenters } = cds.entities('kaizen')
  const plants = [...new Set(rows.map(r => r.plant_ID).filter(Boolean))]
  const known = new Set((await SELECT.from(Plants).columns('ID').where({ ID: { in: plants.length ? plants : ['-'] } })).map(p => p.ID))
  const newPlants = plants.filter(p => !known.has(p)).map(ID => ({ ID, name: `Plant ${ID}` }))
  if (newPlants.length) await INSERT.into(Plants).entries(newPlants)
  const centers = [...new Map(rows.filter(r => r.workCenter_ID).map(r => [r.workCenter_ID, { ID: r.workCenter_ID, name: r.workCenterName, plant_ID: r.plant_ID }])).values()]
  if (centers.length) await UPSERT.into(WorkCenters).entries(centers)
  if (rows.length) await UPSERT.into(Equipment).entries(rows.map(({ workCenterName, ...r }) => r))
  return rows.length
}

export async function lookupEquipment (id) {
  const s4 = await connect('API_EQUIPMENT')
  if (!s4 || !id) return null
  try {
    const e = await s4.run(SELECT.one.from('API_EQUIPMENT.Equipment').where({ Equipment: id }).orderBy('ValidityEndDate desc'))
    if (!e) return null
    const local = toLocal(e)
    await cache([local])
    return local
  } catch (err) { LOG.warn(`equipment ${id} lookup failed:`, err.message); return null }
}

// ponytail: first 1000 machines of a plant; page through $skip when plants have more
export async function syncPlant (plant) {
  const s4 = await connect('API_EQUIPMENT')
  if (!s4) throw Object.assign(new Error('S/4HANA is not connected for this company'), { status: 503 })
  const rows = await s4.run(SELECT.from('API_EQUIPMENT.Equipment').where({ MaintenancePlant: plant, ValidityEndDate: '9999-12-31' }).limit(1000))
  return cache(rows.map(toLocal))
}

export async function createNotification (k) {
  const s4 = await connect('API_MAINTNOTIFICATION')
  if (!s4) throw new Error('S/4HANA is not connected')
  const n = await s4.run(INSERT.into('API_MAINTNOTIFICATION.MaintenanceNotification').entries({
    NotificationText: `${k.number} ${k.title}`.slice(0, 40),
    NotificationType: cds.env.kaizen?.pmNotificationType ?? 'M1',
    TechnicalObject: k.equipment_ID,
    TechObjIsEquipOrFuncnlLoc: 'EAMS_EQUI',
    MaintNotifLongTextForEdit: `Kaizen ${k.number}: ${k.title}\n${k.problem ?? ''}\nOwner: ${k.owner ?? '-'}`.slice(0, 4000)
  }))
  const [row] = n?.[Symbol.iterator] ? [...n] : [n] // local mock: InsertResult (iterable); S/4 over OData: the created entity
  return row?.MaintenanceNotification
}
