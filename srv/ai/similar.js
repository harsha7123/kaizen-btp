import cds from '@sap/cds'

// Duplicate check without AI: word overlap (Jaccard) of title + problem, plus a bonus for the same machine.
// ponytail: compares against the plant's latest 500 kaizens in JS; switch to HANA fuzzy search / embeddings at scale
export const WARN_AT = 0.5
const STOP = new Set(('the and for with from that this was were are has have not but into onto over under near at on in of to a an ' +
  'der die das und mit von bei auf ist sind nicht ein eine im am zu an').split(' '))
const words = s => new Set((s ?? '').toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}\s]/gu, ' ').split(/\s+/)
  .filter(w => w.length > 2 && !STOP.has(w)).map(w => w.replace(/(ing|es|s)$/, '')))

export function score (a, b) {
  const A = words(`${a.title} ${a.problem ?? ''}`), B = words(`${b.title} ${b.problem ?? ''}`)
  if (!A.size || !B.size) return 0
  const both = [...A].filter(w => B.has(w)).length
  const jaccard = both / (A.size + B.size - both)
  return Math.min(1, Math.round((jaccard + (a.equipment_ID && a.equipment_ID === b.equipment_ID ? 0.25 : 0)) * 100) / 100)
}

export async function findSimilar ({ title, problem, equipment_ID, plant_ID, ID }, limit = 3) {
  const { Kaizens, Equipment } = cds.entities('kaizen')
  if (!plant_ID && equipment_ID) plant_ID = (await SELECT.one.from(Equipment, equipment_ID).columns('plant_ID'))?.plant_ID
  if (!title || !plant_ID) return []
  const candidates = await SELECT.from(Kaizens).columns('ID', 'number', 'title', 'problem', 'equipment_ID', 'status_code')
    .where({ plant_ID, status_code: { '!=': 'Rejected' } }).orderBy('createdAt desc').limit(500)
  return candidates.filter(k => k.ID !== ID).map(k => ({ ID: k.ID, number: k.number, title: k.title, status: k.status_code, score: score({ title, problem, equipment_ID }, k) }))
    .filter(k => k.score >= WARN_AT).sort((a, b) => b.score - a.score).slice(0, limit)
}
