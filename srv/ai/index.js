import cds from '@sap/cds'
import stub from './stub.js'
import aicore from './aicore.js'

// AI assist: one small interface, two providers. 'stub' (default, free, offline, deterministic) and 'aicore'
// (SAP AI Core generative AI hub, orchestration). Switch with cds.requires.ai.kind; the same contract tests run
// against both. Whatever a provider returns is untrusted: it is cut to size and checked against allowed values here.
const PROVIDERS = { stub, aicore }
export const PILLARS = ['FI', 'AM', 'PM', 'QM', 'EEM', 'TE', 'SHE', 'OT']

export const provider = () => {
  const kind = cds.env.requires.ai?.kind ?? 'stub'
  const p = PROVIDERS[kind]
  if (!p) throw new Error(`Unknown AI provider '${kind}'`)
  return p
}

const text = (v, max) => typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : null

export async function draftFromPhoto (input) {
  const r = await provider().draft(input)
  return {
    title: text(r?.title, 120),
    problem: text(r?.problem, 2000),
    pillar_code: PILLARS.includes(r?.pillar_code) ? r.pillar_code : null,
    wasteType: text(r?.wasteType, 30),
    isSafety: r?.isSafety === true,
    provider: provider().name
  }
}

export async function fiveWhy (input) {
  const r = await provider().fiveWhy(input)
  const whys = (Array.isArray(r?.whys) ? r.whys : []).slice(0, 5)
    .map(w => ({ question: text(w?.question, 300), answer: text(w?.answer, 500) }))
    .filter(w => w.question && w.answer)
  if (!whys.length) throw new Error('AI returned no usable 5-Why chain')
  return { whys, rootCause: text(r?.rootCause, 2000) ?? whys.at(-1).answer }
}

export const A3_SECTIONS = ['background', 'currentCondition', 'goal', 'rootCause', 'countermeasures', 'results', 'followUp']
export async function a3 (facts) {
  const r = await provider().a3(facts)
  return Object.fromEntries(A3_SECTIONS.map(s => [s, text(r?.[s], 3000) ?? '–']))
}

// cost guard: each user gets a small budget of AI calls per window (per app instance; enough for a demo and a pilot)
const WINDOW = 10 * 60 * 1000, BUDGET = 30, used = new Map()
export function spend (req) {
  const key = `${req.tenant ?? '-'}:${req.user.id}`, now = Date.now()
  const calls = (used.get(key) ?? []).filter(t => now - t < WINDOW)
  if (calls.length >= BUDGET) return req.reject(429, 'Too many AI requests, try again in a few minutes')
  calls.push(now)
  used.set(key, calls)
}
