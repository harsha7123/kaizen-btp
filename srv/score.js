import cds from '@sap/cds'

// Gamification (Phase 7): points and badges are computed from the kaizens themselves, never stored,
// so they cannot drift from the facts or be edited. Names shown are the part of the user ID before '@'.
export const POINTS = { submitted: 10, safety: 10, approved: 10, closed: 30, owned: 20, per1000: 1, deployed: 15 }
const PAST_APPROVAL = ['Approved', 'InProgress', 'Verification', 'Closed']
const BADGES = [
  ['🌱 First kaizen', s => s.submitted >= 1],
  ['🦺 Safety eye', s => s.safety >= 1],
  ['🏁 Closer', s => s.owned >= 3],
  ['💶 10k saver', s => s.verifiedSaving >= 10000],
  ['🎓 Teacher', s => s.deployedFrom >= 1],
  ['🔥 Kaizen machine', s => s.submitted >= 10]
]
export const displayName = user => (user ?? '').split('@')[0]

// ponytail: aggregates all kaizens of the tenant in JS; fine for thousands, use a CDS view beyond that
export async function scores () {
  const { Kaizens, Benefits, Plants } = cds.entities('kaizen')
  const [kaizens, saved, plants] = await Promise.all([
    SELECT.from(Kaizens).columns('ID', 'createdBy', 'owner', 'status_code', 'isSafety', 'plant_ID', 'origin_ID'),
    SELECT.from(Benefits).columns('kaizen_ID', 'annualSaving').where({ verified: true }),
    SELECT.from(Plants).columns('ID', 'name')
  ])
  const byId = new Map(kaizens.map(k => [k.ID, k])), users = new Map()
  const user = id => users.get(id) ?? users.set(id, { user: id, name: displayName(id), points: 0, submitted: 0, safety: 0, closed: 0, owned: 0, verifiedSaving: 0, deployedFrom: 0, plants: {} }).get(id)
  for (const b of saved) { const k = byId.get(b.kaizen_ID); if (k) user(k.createdBy).verifiedSaving += Number(b.annualSaving ?? 0) }
  for (const k of kaizens) {
    if (k.status_code === 'Closed' && k.owner) user(k.owner).owned++
    const origin = k.origin_ID && byId.get(k.origin_ID)
    if (origin) { user(origin.createdBy).deployedFrom++; continue } // a copy is not a new idea: its author earns, not the copier
    const u = user(k.createdBy)
    u.submitted++; u.plants[k.plant_ID] = (u.plants[k.plant_ID] ?? 0) + 1
    if (k.isSafety) u.safety++
    if (PAST_APPROVAL.includes(k.status_code)) u.points += POINTS.approved
    if (k.status_code === 'Closed') u.closed++
  }
  return [...users.values()].map(u => {
    const plant = Object.entries(u.plants).sort((a, b) => b[1] - a[1])[0]?.[0] ?? null
    const points = u.points + u.submitted * POINTS.submitted + u.safety * POINTS.safety + u.closed * POINTS.closed +
      u.owned * POINTS.owned + Math.floor(u.verifiedSaving / 1000) * POINTS.per1000 + u.deployedFrom * POINTS.deployed
    const { plants: _, ...rest } = u
    return { ...rest, points, plant, plantName: plants.find(p => p.ID === plant)?.name ?? plant, badges: BADGES.filter(([, ok]) => ok(u)).map(([b]) => b) }
  }).sort((a, b) => b.points - a.points || a.name.localeCompare(b.name))
}
