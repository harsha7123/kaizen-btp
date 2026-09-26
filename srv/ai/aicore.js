import cds from '@sap/cds'

// SAP AI Core, generative AI hub, orchestration service (REST, no SDK needed).
// Config (package.json or env):  cds.requires.ai = { kind: 'aicore', model: 'gpt-4o', resourceGroup: 'default',
//   deploymentUrl: '<AI_API_URL>/v2/inference/deployments/<orchestration deployment id>' }
// Credentials: the 'aicore' service binding (VCAP_SERVICES on BTP), or env AICORE_SERVICE_KEY with the service key JSON.
const LOG = cds.log('ai')
let token // { value, expires }

const conf = () => {
  const ai = cds.env.requires.ai ?? {}
  const cred = cds.env.requires.aicore?.credentials ?? (process.env.AICORE_SERVICE_KEY && JSON.parse(process.env.AICORE_SERVICE_KEY))
  if (!cred || !ai.deploymentUrl) throw new Error('AI Core is not configured: bind the aicore service and set cds.requires.ai.deploymentUrl')
  return { cred, url: ai.deploymentUrl.replace(/\/$/, ''), model: ai.model ?? 'gpt-4o', group: ai.resourceGroup ?? 'default' }
}

async function accessToken (cred) {
  if (token && token.expires > Date.now() + 60_000) return token.value
  const res = await fetch(`${cred.url.replace(/\/$/, '')}/oauth/token`, {
    method: 'POST',
    headers: { authorization: 'Basic ' + Buffer.from(`${cred.clientid}:${cred.clientsecret}`).toString('base64'), 'content-type': 'application/x-www-form-urlencoded' },
    body: 'grant_type=client_credentials',
    signal: AbortSignal.timeout(15_000)
  })
  if (!res.ok) throw new Error(`AI Core login failed: HTTP ${res.status}`)
  const t = await res.json()
  token = { value: t.access_token, expires: Date.now() + (t.expires_in ?? 3600) * 1000 }
  return token.value
}

// one chat turn -> the model's JSON answer
async function chat (system, user) {
  const { cred, url, model, group } = conf()
  const res = await fetch(`${url}/completion`, {
    method: 'POST',
    headers: { authorization: `Bearer ${await accessToken(cred)}`, 'ai-resource-group': group, 'content-type': 'application/json' },
    body: JSON.stringify({
      orchestration_config: {
        module_configurations: {
          templating_module_config: { template: [{ role: 'system', content: system }, { role: 'user', content: user }] },
          llm_module_config: { model_name: model, model_params: { temperature: 0.2, max_tokens: 1200 } }
        }
      },
      input_params: {}
    }),
    signal: AbortSignal.timeout(45_000)
  })
  if (!res.ok) { LOG.warn('AI Core', res.status, (await res.text()).slice(0, 300)); throw new Error(`AI Core request failed: HTTP ${res.status}`) }
  const content = (await res.json())?.orchestration_result?.choices?.[0]?.message?.content ?? ''
  const json = content.match(/\{[\s\S]*\}/)?.[0] // models sometimes wrap JSON in prose or code fences
  if (!json) throw new Error('AI Core returned no JSON')
  return JSON.parse(json)
}

const RULES = 'You help TPM / kaizen teams in manufacturing plants. Answer with one JSON object only, no prose. Be concrete and short. Never invent measurements.'

export default {
  name: 'aicore',

  draft: ({ image, machine, hint, lang }) => chat(
    `${RULES} Keys: title (max 80 chars), problem (2-3 sentences: what, where, since when if visible), pillar_code (one of FI, AM, PM, QM, EEM, TE, SHE, OT), wasteType (e.g. Defects, Waiting, Motion, Leak), isSafety (boolean). Language: ${lang || 'en'}.`,
    [
      { type: 'text', text: `Machine: ${machine ? `${machine.name}, ${machine.plantName}, ${machine.workCenter_ID ?? ''}` : 'unknown'}. Operator note: ${hint || 'none'}. Describe the abnormality in the photo.` },
      ...(image ? [{ type: 'image_url', image_url: { url: `data:image/jpeg;base64,${image}` } }] : [])
    ]),

  fiveWhy: ({ title, problem, machine }) => chat(
    `${RULES} Keys: whys (array of exactly 5 objects {question, answer}, each answer is the cause of the previous one), rootCause (one sentence, systemic cause, not a person).`,
    `Machine: ${machine ?? 'unknown'}. Problem: ${title}. ${problem ?? ''}`),

  a3: facts => chat(
    `${RULES} Write an A3 report. Keys: background, currentCondition, goal, rootCause, countermeasures, results, followUp (each 1-4 short lines, use the facts only).`,
    JSON.stringify(facts))
}
