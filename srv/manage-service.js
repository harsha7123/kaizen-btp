import cds from '@sap/cds'
import KaizenService, { isAny } from './kaizen-service.js'

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
    const CAN = ['canApprove', 'canStart', 'canRequestVerification', 'canClose']
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

    await super.init()
    // translate the inbox filter before CAP's draft layer (installed on this.handle by super.init) splits the query
    const draftHandle = this.handle
    this.handle = function (req) {
      if (req.event === 'READ' && req.query?.SELECT?.where && req.target?.name.startsWith('ManageService.Kaizens')) rewrite(req.query.SELECT.where, req.user)
      return draftHandle.call(this, req)
    }
  }
}
