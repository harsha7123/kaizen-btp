import cds from '@sap/cds'

// Kaizen lifecycle: Submitted -> InReview -> Approved -> InProgress -> Verification -> Closed
//                            \-> Rejected (from Submitted / InReview)
export default class KaizenService extends cds.ApplicationService {
  init() {
    const { Kaizens, Photos, Tasks, Benefits, WorkflowRoutes, StatusHistory } = cds.entities('kaizen')

    const load = async req => {
      const k = await SELECT.one.from(req.subject)
      if (!k) return req.reject(404, 'Kaizen not found')
      return k
    }

    const move = async (req, k, to, patch = {}, note) => {
      await UPDATE(Kaizens, k.ID).with({ status_code: to, ...patch })
      await INSERT.into(StatusHistory).entries({ kaizen_ID: k.ID, fromStatus: k.status_code, toStatus: to, note })
      return SELECT.one.from(Kaizens, k.ID)
    }

    const approversOf = async k => {
      const route = k.route_ID && await SELECT.one.from(WorkflowRoutes, k.route_ID)
      const list = route ? route.approvers.split(',').map(s => s.trim()) : ['Supervisor']
      if (k.isSafety && !list.includes('EHS')) list.push('EHS') // safety kaizens always get an EHS step
      return list
    }

    // ---- create: number + routing ----
    this.before('CREATE', 'Kaizens', async req => {
      const d = req.data
      const year = new Date().getFullYear()
      const { max } = await SELECT.one.from(Kaizens).columns`max(number) as max`.where`number like ${`KAI-${year}-%`}`
      // ponytail: max+1 can collide under concurrent inserts; use a HANA sequence when volume matters
      d.number = `KAI-${year}-${String((max ? +max.slice(-4) : 0) + 1).padStart(4, '0')}`
      d.status_code = 'Submitted'
      d.step = 0
      if (d.equipment_ID && !d.plant_ID) {
        const eq = await SELECT.one.from('kaizen.Equipment', d.equipment_ID)
        if (!eq) return req.reject(400, `Unknown equipment ${d.equipment_ID}`, 'equipment_ID')
        d.plant_ID = eq.plant_ID
      }
      if (!d.plant_ID) return req.reject(400, 'Scan equipment or choose a plant', 'plant_ID')
      const routes = await SELECT.from(WorkflowRoutes)
        .where`(plant_ID = ${d.plant_ID} or plant_ID is null) and (pillar_code = ${d.pillar_code} or pillar_code is null)`
        .orderBy`priority`
      const route = routes.find(r => r.maxBenefit == null || (d.estimatedBenefit ?? 0) <= +r.maxBenefit)
      d.route_ID = route?.ID
    })

    this.after('CREATE', 'Kaizens', (_, req) => INSERT.into(StatusHistory).entries({ kaizen_ID: req.data.ID, toStatus: 'Submitted' }))

    // ---- approval chain ----
    this.on('approve', 'Kaizens', async req => {
      const k = await load(req)
      if (!['Submitted', 'InReview'].includes(k.status_code)) return req.reject(409, `Cannot approve a kaizen in status ${k.status_code}`)
      const approvers = await approversOf(k)
      const role = approvers[k.step]
      if (!req.user.is(role) && !req.user.is('Admin')) return req.reject(403, `Step ${k.step + 1} must be approved by ${role}`)
      const step = k.step + 1
      return move(req, k, step >= approvers.length ? 'Approved' : 'InReview', { step }, req.data.note)
    })

    this.on('reject', 'Kaizens', async req => {
      const k = await load(req)
      if (!['Submitted', 'InReview'].includes(k.status_code)) return req.reject(409, `Cannot reject a kaizen in status ${k.status_code}`)
      const role = (await approversOf(k))[k.step]
      if (!req.user.is(role) && !req.user.is('Admin')) return req.reject(403, `Step ${k.step + 1} must be decided by ${role}`)
      return move(req, k, 'Rejected', {}, req.data.note)
    })

    this.on('start', 'Kaizens', async req => {
      const k = await load(req)
      if (k.status_code !== 'Approved') return req.reject(409, 'Only approved kaizens can be started')
      const { owner, dueDate } = req.data
      return move(req, k, 'InProgress', { owner, dueDate })
    })

    this.on('requestVerification', 'Kaizens', async req => {
      const k = await load(req)
      if (k.status_code !== 'InProgress') return req.reject(409, 'Only kaizens in progress can go to verification')
      if (k.owner !== req.user.id && !req.user.is('CIManager') && !req.user.is('PlantManager'))
        return req.reject(403, 'Only the owner or a CI manager can request verification')
      return move(req, k, 'Verification')
    })

    // ---- verification gate: nothing closes without evidence ----
    this.on('close', 'Kaizens', async req => {
      const k = await load(req)
      if (k.status_code !== 'Verification') return req.reject(409, 'Only kaizens in verification can be closed')
      const [after, verified, open] = await Promise.all([
        SELECT.one.from(Photos).columns`count(*) as n`.where({ kaizen_ID: k.ID, kind: 'After' }),
        SELECT.one.from(Benefits).columns`count(*) as n`.where({ kaizen_ID: k.ID, verified: true }),
        SELECT.one.from(Tasks).columns`count(*) as n`.where({ kaizen_ID: k.ID, done: false })
      ])
      const missing = [
        !after.n && 'at least one After photo',
        !verified.n && 'at least one verified benefit',
        open.n && `${open.n} open task(s) completed`
      ].filter(Boolean)
      if (missing.length) return req.reject(409, `Verification gate: needs ${missing.join(', ')}`)
      return move(req, k, 'Closed', {}, req.data.note)
    })

    // ---- children: no edits after closure; only CI managers verify benefits ----
    this.before(['CREATE', 'UPDATE'], ['Photos', 'Tasks', 'Benefits'], async req => {
      const kaizenID = req.data.kaizen_ID ?? (await SELECT.one.from(req.subject).columns('kaizen_ID'))?.kaizen_ID
      if (!kaizenID) return req.reject(400, 'kaizen_ID is required', 'kaizen_ID')
      const k = await SELECT.one.from(Kaizens, kaizenID).columns('status_code')
      if (!k) return req.reject(400, `Unknown kaizen ${kaizenID}`, 'kaizen_ID')
      if (['Closed', 'Rejected'].includes(k.status_code)) return req.reject(409, `Kaizen is ${k.status_code}`)
      if (req.target.name.endsWith('Benefits') && req.data.verified && !req.user.is('CIManager') && !req.user.is('PlantManager'))
        return req.reject(403, 'Only a CI manager can verify a benefit', 'verified')
    })

    // deep create/update could smuggle a verified benefit past the child handler above
    this.before(['CREATE', 'UPDATE'], 'Kaizens', req => {
      if (req.data.benefits?.some(b => b.verified) && !req.user.is('CIManager') && !req.user.is('PlantManager'))
        return req.reject(403, 'Only a CI manager can verify a benefit')
    })

    this.before('UPDATE', 'Kaizens', async req => {
      const k = await SELECT.one.from(req.subject).columns('status_code')
      if (k && ['Closed', 'Rejected'].includes(k.status_code)) return req.reject(409, `Kaizen is ${k.status_code}`)
    })

    return super.init()
  }
}
