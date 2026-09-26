import cds from '@sap/cds'
import KaizenService, { isAny } from './kaizen-service.js'
import { fiveWhy, a3, spend } from './ai/index.js'
import { syncPlant } from './s4.js'
import { scores } from './score.js'

const DECIDERS = ['Supervisor', 'CIManager', 'PlantManager', 'EHS']
const CLOSERS = ['CIManager', 'PlantManager', 'Admin']
const DAY = 24 * 3600 * 1000

// inbox: "waitingForMe eq true" becomes: next step is one of my roles, or I own the kaizen while it is in progress
const mine = user => {
  const next = { ref: ['nextRole'] }
  if (user.is('Admin')) return { xpr: [next, 'is', 'not', 'null'] }
  const roles = DECIDERS.filter(r => user.is(r))
  return { xpr: [next, 'in', { list: (roles.length ? roles : ['-']).map(val => ({ val })) }, 'or',
    { xpr: [next, '=', { val: 'Owner' }, 'and', { ref: ['owner'] }, '=', { val: user.id }] }] }
}
const rewrite = (xs, user) => {
  for (let i = 0; i < xs.length; i++) {
    if (xs[i]?.xpr) rewrite(xs[i].xpr, user)
    if (xs[i]?.ref?.at(-1) === 'waitingForMe' && ['=', '!='].includes(xs[i + 1])) {
      const yes = [true, 'true'].includes(xs[i + 2]?.val) === (xs[i + 1] === '=')
      xs.splice(i, 3, yes ? mine(user) : { xpr: ['not', mine(user)] })
    }
  }
}

// Fiori backend: inherits every workflow rule from KaizenService, adds the inbox, button visibility and KPIs
export default class ManageService extends KaizenService {
  async init() {
    const { Kaizens, Benefits, Plants, Pillars } = cds.entities('kaizen')

    // button visibility: computed from the same facts the action handlers check
    const CAN = ['canApprove', 'canStart', 'canRequestVerification', 'canClose', 'canAnalyze', 'canDeploy']
    this.before('READ', 'Kaizens', req => {
      const cols = req.query.SELECT.columns
      if (cols && cols.some(c => CAN.includes(c.ref?.[0])))
        for (const f of ['status_code', 'nextRole', 'owner']) if (!cols.some(c => c.ref?.[0] === f)) cols.push({ ref: [f] })
    })
    this.after('READ', 'Kaizens', (rows, req) => {
      const u = req.user
      for (const k of [rows].flat()) {
        if (!k || !('status_code' in k)) continue
        k.canApprove = ['Submitted', 'InReview'].includes(k.status_code) && (u.is(k.nextRole) || u.is('Admin'))
        k.canStart = k.status_code === 'Approved' && isAny(u, CLOSERS)
        k.canRequestVerification = k.status_code === 'InProgress' && (k.owner === u.id || isAny(u, CLOSERS))
        k.canClose = k.status_code === 'Verification' && isAny(u, CLOSERS)
        k.canAnalyze = !['Closed', 'Rejected'].includes(k.status_code)
        k.canDeploy = k.status_code === 'Closed' && isAny(u, CLOSERS)
      }
    })

    // KPIs per plant x pillar plus a total row
    // ponytail: aggregated in JS over all kaizens; move to a CDS view with group by when a tenant has >10k kaizens
    this.on('READ', 'Kpis', async () => {
      const [kaizens, saved, plants, pillars] = await Promise.all([
        SELECT.from(Kaizens).columns('ID', 'plant_ID', 'pillar_code', 'status_code', 'createdAt', 'closedAt'),
        SELECT.from(Benefits).columns('kaizen_ID', 'annualSaving').where({ verified: true }),
        SELECT.from(Plants), SELECT.from(Pillars)
      ])
      const saving = {}
      for (const b of saved) saving[b.kaizen_ID] = (saving[b.kaizen_ID] ?? 0) + Number(b.annualSaving ?? 0)
      const groups = {}
      const add = (plant, pillar, k) => {
        const g = groups[`${plant}|${pillar}`] ??= { plant, pillar, kaizens: 0, open: 0, closed: 0, cycle: [], verifiedSaving: 0 }
        g.kaizens++
        if (k.status_code === 'Closed') { g.closed++; g.cycle.push((new Date(k.closedAt) - new Date(k.createdAt)) / DAY) }
        else if (k.status_code !== 'Rejected') g.open++
        g.verifiedSaving += saving[k.ID] ?? 0
      }
      for (const k of kaizens) { add(k.plant_ID, k.pillar_code, k); add('ALL', 'ALL', k) }
      const rows = Object.values(groups).map(({ cycle, ...g }) => ({
        ...g,
        plantName: g.plant === 'ALL' ? 'All plants' : plants.find(p => p.ID === g.plant)?.name ?? g.plant,
        pillarName: g.pillar === 'ALL' ? 'All pillars' : pillars.find(p => p.code === g.pillar)?.name ?? g.pillar,
        avgCycleDays: cycle.length ? Math.round(cycle.reduce((a, b) => a + b, 0) / cycle.length * 10) / 10 : null,
        verifiedSaving: Math.round(g.verifiedSaving * 100) / 100
      })).sort((a, b) => (a.plant === 'ALL') - (b.plant === 'ALL') || a.plant.localeCompare(b.plant) || a.pillar.localeCompare(b.pillar))
      rows.$count = rows.length
      return rows
    })

    // ---- AI assist: 5-Why and A3, written straight to the active kaizen ----
    const { Tasks, Equipment, Statuses } = cds.entities('kaizen')
    const facts = async ID => {
      const k = await SELECT.one.from(Kaizens, ID)
      const [plant, pillar, eq, status, tasks, benefits] = await Promise.all([
        SELECT.one.from(Plants, k.plant_ID), SELECT.one.from(Pillars, k.pillar_code), k.equipment_ID && SELECT.one.from(Equipment, k.equipment_ID),
        SELECT.one.from(Statuses, k.status_code),
        SELECT.from(Tasks).columns('title', 'owner', 'done').where({ kaizen_ID: ID }),
        SELECT.from(Benefits).columns('type', 'baseline', 'improved', 'unit', 'annualSaving', 'verified').where({ kaizen_ID: ID })
      ])
      return { ...k, plantName: plant?.name ?? k.plant_ID, pillarName: pillar?.name ?? k.pillar_code, machine: eq?.name, status: status?.name ?? k.status_code, tasks, benefits }
    }
    const ask = async (req, fn) => {
      await spend(req)
      try { return await fn() } catch (e) { cds.log('ai').warn(e.message); return req.reject(502, 'The AI assistant is not available right now') }
    }
    this.on('fiveWhy', 'Kaizens', async req => {
      const { ID } = await SELECT.one.from(req.subject).columns('ID')
      const k = await facts(ID)
      if (['Closed', 'Rejected'].includes(k.status_code)) return req.reject(409, `Kaizen is ${k.status_code}`)
      const r = await ask(req, () => fiveWhy({ title: k.title, problem: k.problem, machine: k.machine }))
      const text = r.whys.map((w, i) => `${i + 1}. ${w.question}\n   → ${w.answer}`).join('\n') + `\nRoot cause: ${r.rootCause}`
      await UPDATE(Kaizens, ID).with({ fiveWhy: text, ...(!k.rootCause && { rootCause: r.rootCause }) })
      return SELECT.one.from(req.subject)
    })
    this.on('generateA3', 'Kaizens', async req => {
      const { ID } = await SELECT.one.from(req.subject).columns('ID')
      const k = await facts(ID)
      const report = await ask(req, () => a3({ ...k, createdAt: new Date(k.createdAt).toISOString() }))
      await UPDATE(Kaizens, ID).with({ a3: JSON.stringify(report) })
      return SELECT.one.from(req.subject)
    })

    // ---- gamification and horizontal deployment ----
    this.on('READ', 'Leaderboard', async () => {
      const rows = (await scores()).map((s, i) => ({ // only the declared fields go out
        user: s.user, rank: i + 1, name: s.name, plantName: s.plantName, points: s.points, submitted: s.submitted,
        closed: s.closed, owned: s.owned, verifiedSaving: s.verifiedSaving, badges: s.badges.join('  ')
      }))
      rows.$count = rows.length
      return rows
    })

    this.on('deployTo', 'Kaizens', async req => {
      const src = await SELECT.one.from(req.subject)
      const target = req.data.equipment_ID?.trim().toUpperCase()
      if (src.status_code !== 'Closed') return req.reject(409, 'Only closed (proven) kaizens can be deployed to other machines')
      if (!target || target === src.equipment_ID) return req.reject(400, 'Choose a different machine', 'equipment_ID')
      // created through the phone-facing service: same numbering, routing, S/4 lookup and permission checks as any kaizen
      const ks = cds.services.KaizenService, ID = cds.utils.uuid()
      await ks.run(INSERT.into(ks.entities.Kaizens).entries({
        ID, equipment_ID: target, pillar_code: src.pillar_code, isSafety: src.isSafety, estimatedBenefit: src.estimatedBenefit,
        title: src.title, rootCause: src.rootCause, countermeasure: src.countermeasure,
        problem: `Horizontal deployment of ${src.number} (${src.equipment_ID}): ${src.problem ?? src.title}`.slice(0, 2000)
      }))
      const copy = await SELECT.one.from(Kaizens, ID).columns('number', 'similarTo_ID')
      // the copy is not a duplicate of its origin, it is a deployment of it
      await UPDATE(Kaizens, ID).with({ origin_ID: src.ID, ...(copy.similarTo_ID === src.ID && { similarTo_ID: null, similarity: null }) })
      req.info(`Created ${copy.number} for machine ${target}`)
      return SELECT.one.from(req.subject)
    })

    this.on('syncEquipment', async req => {
      try { return await syncPlant(req.data.plant) }
      catch (e) { return req.reject(e.status ?? 502, e.status ? e.message : `S/4HANA sync failed: ${e.message}`) }
    })

    await super.init()
    // translate the inbox filter before CAP's draft layer (installed on this.handle by super.init) splits the query
    const draftHandle = this.handle
    this.handle = function (req) {
      if (req.event === 'READ' && req.query?.SELECT?.where && req.target?.name.startsWith('ManageService.Kaizens')) rewrite(req.query.SELECT.where, req.user)
      return draftHandle.call(this, req)
    }
  }
}
