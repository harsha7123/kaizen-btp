import cds from '@sap/cds'

// Kaizen lifecycle: Submitted -> InReview -> Approved -> InProgress -> Verification -> Closed
//                            \-> Rejected (from Submitted / InReview)
// Shared by KaizenService (phone app, non-draft) and ManageService (Fiori, draft), which extends this class.
export const MANAGERS = ['Supervisor', 'CIManager', 'PlantManager', 'EHS', 'Admin']
const VERIFIERS = ['CIManager', 'PlantManager', 'Admin']
const WORKFLOW_FIELDS = ['number', 'status_code', 'step', 'route_ID', 'nextRole', 'closedAt']
const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const MAX_PHOTO_BYTES = 5 * 1024 * 1024 // the phone sends ~0.3 MB; anything near this limit is not a phone photo

export const isAny = (user, roles) => roles.some(r => user.is(r))

export default class KaizenService extends cds.ApplicationService {
  init() {
    const { Kaizens, Photos, Tasks, Benefits, WorkflowRoutes, StatusHistory } = cds.entities('kaizen')

    const load = async req => {
      const k = await SELECT.one.from(req.subject)
      if (!k) return req.reject(404, 'Kaizen not found')
      return k
    }

    const approversOf = async k => {
      const route = k.route_ID && await SELECT.one.from(WorkflowRoutes, k.route_ID)
      const list = route ? route.approvers.split(',').map(s => s.trim()) : ['Supervisor']
      if (k.isSafety && !list.includes('EHS')) list.push('EHS') // safety kaizens always get an EHS step
      return list
    }

    // who acts next: drives the inbox and which buttons the Fiori app shows
    const nextRoleOf = async (k, status) => ({
      Submitted: () => approversOf(k).then(a => a[k.step]),
      InReview: () => approversOf(k).then(a => a[k.step]),
      Approved: () => 'CIManager', // start
      InProgress: () => 'Owner', // do the work, then request verification
      Verification: () => 'CIManager' // verify and close
    })[status]?.() ?? null

    const move = async (req, k, to, patch = {}, note) => {
      patch.nextRole = await nextRoleOf({ ...k, ...patch }, to)
      if (to === 'Closed') patch.closedAt = new Date().toISOString()
      await UPDATE(Kaizens, k.ID).with({ status_code: to, ...patch })
      await INSERT.into(StatusHistory).entries({ kaizen_ID: k.ID, fromStatus: k.status_code, toStatus: to, note })
      return SELECT.one.from(req.subject)
    }

    // ---- create: number + routing ----
    this.before('CREATE', 'Kaizens', async req => {
      const d = req.data
      // offline capture retries with the ID generated on the phone: 409 tells it the kaizen already arrived
      if (d.ID && await SELECT.one.from(Kaizens, d.ID).columns('ID')) return req.reject(409, `Kaizen ${d.ID} already exists`)
      const year = new Date().getFullYear()
      const { max } = await SELECT.one.from(Kaizens).columns`max(number) as max`.where`number like ${`KAI-${year}-%`}`
      // ponytail: max+1 can collide under concurrent inserts; use a HANA sequence when volume matters
      d.number = `KAI-${year}-${String((max ? +max.slice(-4) : 0) + 1).padStart(4, '0')}`
      d.status_code = 'Submitted'
      d.step = 0
      d.closedAt = null
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
      d.nextRole = await nextRoleOf(d, 'Submitted')
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
      if (k.owner !== req.user.id && !isAny(req.user, VERIFIERS))
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

    // ---- children: no edits after closure; photos only on your own kaizens; only CI managers touch verified benefits ----
    this.before(['CREATE', 'UPDATE'], ['Photos', 'Tasks', 'Benefits'], async req => {
      const entity = req.target.name.split('.').pop()
      if (req.event === 'CREATE' && req.data.ID && await SELECT.one.from(req.target, req.data.ID).columns('ID'))
        return req.reject(409, `${entity} ${req.data.ID} already exists`)
      const existing = req.event === 'UPDATE' ? await SELECT.one.from(req.subject) : null
      const kaizenID = req.data.kaizen_ID ?? existing?.kaizen_ID
      if (!kaizenID) return req.reject(400, 'kaizen_ID is required', 'kaizen_ID')
      const k = await SELECT.one.from(Kaizens, kaizenID).columns('status_code', 'createdBy', 'owner')
      if (!k) return req.reject(400, `Unknown kaizen ${kaizenID}`, 'kaizen_ID')
      if (['Closed', 'Rejected'].includes(k.status_code)) return req.reject(409, `Kaizen is ${k.status_code}`)

      if (entity === 'Photos') {
        if (req.event === 'CREATE' && !isAny(req.user, MANAGERS) && ![k.createdBy, k.owner].includes(req.user.id))
          return req.reject(403, 'You can only add photos to your own kaizens')
        if (req.data.mediaType && !IMAGE_TYPES.includes(req.data.mediaType))
          return req.reject(415, `Photos must be ${IMAGE_TYPES.join(', ')}`, 'mediaType')
        const size = +(req.http?.req?.headers['content-length'] ?? 0)
        if (req.data.content && size > MAX_PHOTO_BYTES) return req.reject(413, 'Photo is larger than 5 MB')
      }
      if (entity === 'Benefits' && !isAny(req.user, VERIFIERS)) {
        if (req.data.verified) return req.reject(403, 'Only a CI manager can verify a benefit', 'verified')
        if (existing?.verified) return req.reject(403, 'Only a CI manager can change a verified benefit')
      }
    })

    // deep writes (Fiori draft activation, deep POST) must not verify, change or drop a verified benefit either
    this.before(['CREATE', 'UPDATE'], 'Kaizens', async req => {
      const incoming = req.data.benefits
      if (!incoming || isAny(req.user, VERIFIERS)) return
      const before = req.event === 'UPDATE' ? await SELECT.from(Benefits).where({ kaizen_ID: req.data.ID, verified: true }) : []
      const sent = new Map(incoming.map(b => [b.ID, b]))
      const same = (a, b) => a.verified === b.verified && ['type', 'unit'].every(f => a[f] === b[f]) &&
        ['baseline', 'improved', 'annualSaving'].every(f => Number(a[f] ?? 0) === Number(b[f] ?? 0))
      if (incoming.some(b => b.verified && !before.some(v => v.ID === b.ID)) || before.some(v => !sent.has(v.ID) || !same(v, { ...v, ...sent.get(v.ID) })))
        return req.reject(403, 'Only a CI manager can verify, change or remove a verified benefit')
    })

    this.before('UPDATE', 'Kaizens', async req => {
      for (const f of WORKFLOW_FIELDS) delete req.data[f] // workflow fields only move through actions (a stale draft must not roll them back)
      const k = await SELECT.one.from(req.subject).columns('status_code')
      if (k && ['Closed', 'Rejected'].includes(k.status_code)) return req.reject(409, `Kaizen is ${k.status_code}`)
      if (k && !isAny(req.user, MANAGERS) && k.status_code !== 'Submitted')
        return req.reject(409, 'Operators can only edit a kaizen until the first approval')
    })

    return super.init()
  }
}
