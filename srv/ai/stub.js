// Stand-in AI for the free trial: deterministic, no network. It cannot see the photo, so it drafts from the machine
// and whatever the operator already typed; the A3 is assembled from the kaizen's real data (useful without any AI).
const eur = n => `EUR ${Number(n).toLocaleString('en-US', { maximumFractionDigits: 0 })}`

export default {
  name: 'stub',

  async draft ({ machine, hint }) {
    const where = machine ? `${machine.name} (${machine.plantName}, ${machine.workCenter_ID ?? 'line'})` : 'the machine'
    return {
      title: hint || (machine ? `Abnormality at ${machine.name}` : 'Abnormality found at the machine'),
      problem: `Found at ${where}. What is wrong, since when, how often?`,
      pillar_code: 'AM',
      wasteType: 'Defects',
      isSafety: /leak|guard|fire|smoke|spark|slip|trip|sharp/i.test(hint ?? '')
    }
  },

  async fiveWhy ({ title, problem }) {
    const what = (title || problem || 'the problem').replace(/\.$/, '')
    const whys = [
      [`Why does "${what}" happen?`, 'The part wears or is set up differently than the standard (check at the machine).'],
      ['Why does it wear or deviate?', 'The condition is not checked during the shift (no inspection point).'],
      ['Why is it not checked?', 'The autonomous maintenance checklist does not include it.'],
      ['Why is it not on the checklist?', 'The checklist was written before this failure mode was known.'],
      ['Why was the checklist not updated?', 'There is no routine to update standards after a breakdown.']
    ].map(([question, answer]) => ({ question, answer }))
    return { whys, rootCause: 'Standards are not updated after breakdowns, so the failure mode is not inspected (confirm at the gemba).' }
  },

  async a3 (k) {
    const done = k.tasks.filter(t => t.done).length
    const benefits = k.benefits.map(b =>
      `${b.type}: ${b.baseline ?? '?'} → ${b.improved ?? '?'} ${b.unit ?? ''}${b.annualSaving ? `, ${eur(b.annualSaving)}/yr` : ''}${b.verified ? ' (verified)' : ' (not verified yet)'}`)
    return {
      background: `${k.pillarName} kaizen ${k.number} on ${k.machine ?? 'no machine'} (${k.plantName}), reported by ${k.createdBy} on ${k.createdAt.slice(0, 10)}.${k.isSafety ? ' Safety relevant.' : ''}`,
      currentCondition: k.problem || k.title,
      goal: k.estimatedBenefit ? `Eliminate the problem; expected saving ${eur(k.estimatedBenefit)}/yr.` : 'Eliminate the problem and prevent recurrence.',
      rootCause: k.rootCause || (k.fiveWhy ? k.fiveWhy.split('\n').at(-1) : 'Not analysed yet: run the 5-Why analysis.'),
      countermeasures: [k.countermeasure, ...k.tasks.map(t => `${t.done ? '✔' : '☐'} ${t.title}${t.owner ? ` (${t.owner})` : ''}`)].filter(Boolean).join('\n') || 'No countermeasures planned yet.',
      results: benefits.join('\n') || 'No results recorded yet.',
      followUp: k.status === 'Closed'
        ? 'Standardise: update the checklist and share with identical machines (horizontal deployment).'
        : `Status ${k.status}: ${done}/${k.tasks.length} tasks done. Next: ${k.nextRole ?? 'none'}.`
    }
  }
}
