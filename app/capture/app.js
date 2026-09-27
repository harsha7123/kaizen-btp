// Kaizen Capture: shop-floor PWA. Every submit goes into an IndexedDB queue first and is synced when online,
// so the same code path works with and without network. IDs are generated on the phone, so a retried sync
// gets 409 from the server instead of creating a duplicate.
const API = '/odata/v4/kaizen'
const $ = id => document.getElementById(id)
const label = (id, text) => { $(id).querySelector('.label').textContent = text } // buttons keep their icon
const state = { mode: 'new', photos: [], pillar: null, pick: null, equipment: [], plants: [], open: [], syncing: false, aiDrafted: false }

// ---- IndexedDB queue ----
const db = new Promise((ok, fail) => {
  const r = indexedDB.open('kaizen-capture', 1)
  r.onupgradeneeded = () => r.result.createObjectStore('queue', { keyPath: 'ID' })
  r.onsuccess = () => ok(r.result)
  r.onerror = () => fail(r.error)
})
const tx = async (mode, fn) => {
  const t = (await db).transaction('queue', mode), store = t.objectStore('queue')
  const req = fn(store)
  return new Promise((ok, fail) => { t.oncomplete = () => ok(req?.result); t.onerror = () => fail(t.error) })
}
const queue = {
  all: () => tx('readonly', s => s.getAll()),
  put: item => tx('readwrite', s => s.put(item)),
  remove: ID => tx('readwrite', s => s.delete(ID))
}
const done = { // synced kaizens, newest first, for the "my kaizens" list
  all: () => JSON.parse(localStorage.getItem('kaizen-done') || '[]'),
  add: k => localStorage.setItem('kaizen-done', JSON.stringify([k, ...done.all()].slice(0, 20)))
}

// ---- sync ----
class Retry extends Error {} // network down, server busy or login expired: keep in queue, try later
// on BTP the app router demands a CSRF token for writes (none locally): fetch once, refresh when it expires
let csrf
const csrfToken = async () => csrf ??= (await fetch(API + '/', { credentials: 'include', headers: { 'x-csrf-token': 'fetch' } })
  .then(r => r.headers.get('x-csrf-token'), () => null)) ?? ''
const send = async (method, path, body, type = 'application/json', retried) => {
  let res
  try {
    const headers = { 'content-type': type, accept: 'application/json' }
    if (method !== 'GET' && await csrfToken()) headers['x-csrf-token'] = csrf
    res = await fetch(API + path, { method, credentials: 'include', headers, body: type === 'application/json' ? JSON.stringify(body) : body })
  } catch { throw new Retry('offline') }
  if (res.status === 403 && res.headers.get('x-csrf-token')?.toLowerCase() === 'required' && !retried) {
    csrf = undefined // expired token: fetch a new one and try once more
    return send(method, path, body, type, true)
  }
  if (res.status === 409 && method === 'POST') return null // already arrived on an earlier attempt
  if (res.status === 401 || res.status === 408 || res.status === 429 || res.status >= 500) throw new Retry(`server ${res.status}`)
  const text = await res.text()
  if (!res.ok) throw new Error(JSON.parse(text || '{}').error?.message || `HTTP ${res.status}`)
  if (text && !res.headers.get('content-type')?.includes('json')) { loginExpired(); throw new Retry('login required') } // app router login page
  return text ? JSON.parse(text) : null
}
// the login session ended (e.g. overnight): kaizens stay queued; reloading the page shows the sign-in screen
let loginToast
function loginExpired () {
  if (loginToast) return
  loginToast = true
  if (confirm('Your sign-in has expired. Sign in again to send your kaizens?')) location.reload()
}

async function sync () {
  if (state.syncing || !navigator.onLine) return
  state.syncing = true
  let synced = 0
  try {
    for (const item of await queue.all()) {
      try {
        const kaizenID = item.kaizenID ?? item.ID // After photo items attach to an existing kaizen
        if (!item.kaizenID) await send('POST', '/Kaizens', { ID: item.ID, ...item.data })
        for (const p of item.photos) {
          await send('POST', '/Photos', { ID: p.ID, kaizen_ID: kaizenID, kind: p.kind, mediaType: p.blob.type })
          await send('PUT', `/Photos(${p.ID})/content`, p.blob, p.blob.type)
        }
        const k = await send('GET', `/Kaizens(${kaizenID})?$select=number,status_code`)
        await queue.remove(item.ID)
        done.add({ ID: item.ID, title: item.data.title, number: k.number, status: k.status_code })
        synced++
      } catch (e) {
        if (e instanceof Retry) break
        await queue.put({ ...item, error: e.message }) // rejected by the server: needs the operator's attention
      }
    }
  } finally {
    state.syncing = false
    if (synced) { toast(`${synced} kaizen${synced > 1 ? 's' : ''} sent`); loadScore() }
    render()
  }
}

// ---- master data (cached for offline scanning) ----
async function loadMasterData () {
  const cached = JSON.parse(localStorage.getItem('kaizen-master') || 'null')
  if (cached) apply(cached)
  try {
    // ponytail: whole equipment list fits a demo; filter by the user's plant when real plants have thousands
    const [eq, pl, pi, open] = await Promise.all([
      send('GET', '/Equipment?$select=ID,name,plant_ID,workCenter_ID&$orderby=ID'),
      send('GET', '/Plants?$select=ID,name'),
      send('GET', '/Pillars?$select=code,name&$orderby=code'),
      send('GET', "/Kaizens?$select=ID,number,title,equipment_ID&$filter=status_code eq 'InProgress' or status_code eq 'Verification'&$orderby=number desc&$top=500")
    ])
    const fresh = { equipment: eq.value, plants: pl.value, pillars: pi.value, open: open.value }
    localStorage.setItem('kaizen-master', JSON.stringify(fresh))
    apply(fresh)
  } catch { if (!cached) toast('Connect once to download machines and pillars') }
}
function apply ({ equipment, plants, pillars, open = [] }) {
  state.open = open // kaizens in progress, for After photos
  state.plants = plants
  state.equipment = equipment.map(e => ({ ...e, plantName: plants.find(p => p.ID === e.plant_ID)?.name ?? e.plant_ID }))
  $('equipment-list').replaceChildren(...equipment.map(e => new Option(e.name, e.ID)))
  $('pillars').replaceChildren(...pillars.map(p => {
    const b = document.createElement('button')
    b.type = 'button'; b.className = 'btn'; b.dataset.code = p.code; b.setAttribute('aria-pressed', p.code === state.pillar)
    b.innerHTML = `${p.code}<small>${p.name.replace(/&/g, '&amp;').replace(/</g, '&lt;')}</small>`
    b.onclick = () => { state.pillar = p.code; $('pillar-err').hidden = true; for (const x of $('pillars').children) x.setAttribute('aria-pressed', x === b) }
    return b
  }))
  showMachine()
}

// ---- machine: QR scan or typed ID ----
const idFrom = text => { // QR may hold the plain ID or a link to this app with ?eq=<ID>
  try { return new URL(text).searchParams.get('eq') ?? text } catch { return text }
}
function setEquipment (text) {
  $('equipment').value = idFrom(text).trim().toUpperCase()
  showMachine()
}
// a machine missing from the phone's list: ask the server once (it looks it up in S/4 and caches it for everyone)
let resolving
function resolveMachine (id) {
  clearTimeout(resolving)
  if (!navigator.onLine || id.length < 3) return
  resolving = setTimeout(async () => {
    try {
      const e = await send('GET', `/Equipment('${encodeURIComponent(id.replace(/'/g, "''"))}')?$select=ID,name,plant_ID,workCenter_ID`)
      if (!e || state.equipment.some(x => x.ID === e.ID)) return
      state.equipment.push({ ...e, plantName: state.plants.find(p => p.ID === e.plant_ID)?.name ?? e.plant_ID })
      const cached = JSON.parse(localStorage.getItem('kaizen-master') || 'null')
      if (cached) { cached.equipment.push(e); localStorage.setItem('kaizen-master', JSON.stringify(cached)) }
      if ($('equipment').value.trim().toUpperCase() === e.ID) showMachine()
    } catch { /* unknown everywhere or offline: the box keeps saying "Unknown machine" */ }
  }, 400)
}

function showMachine () {
  const id = $('equipment').value.trim().toUpperCase(), box = $('machine')
  const e = state.equipment.find(x => x.ID === id)
  if (id && !e) resolveMachine(id)
  box.hidden = !id
  box.classList.toggle('unknown', !e)
  box.innerHTML = `<div class="mi"><svg class="icon"><use href="#i-factory"/></svg></div><div><b>Unknown machine</b><span>Check the ID or scan again</span></div>`
  if (e) { box.querySelector('b').textContent = e.name; box.querySelector('span').textContent = `${e.plantName} · ${e.workCenter_ID ?? ''}` }
  renderPicks(id)
}
function renderPicks (id) {
  const list = id ? state.open.filter(k => k.equipment_ID === id) : []
  if (!list.some(k => k.ID === state.pick)) state.pick = null
  $('picks-hint').textContent = !id ? 'Scan the machine to see its kaizens in progress.' : list.length ? '' : 'No kaizen in progress on this machine.'
  $('picks').replaceChildren(...list.map(k => {
    const b = document.createElement('button'), n = document.createElement('b'), t = document.createElement('span')
    b.type = 'button'; b.setAttribute('aria-pressed', k.ID === state.pick)
    n.textContent = k.number; t.textContent = k.title
    b.append(n, t)
    b.onclick = () => { state.pick = k.ID; $('pick-err').hidden = true; for (const x of $('picks').children) x.setAttribute('aria-pressed', x === b) }
    return b
  }))
}
function setMode (mode) {
  state.mode = mode
  document.body.classList.toggle('after', mode === 'after')
  $('tab-new').setAttribute('aria-selected', mode === 'new')
  $('tab-after').setAttribute('aria-selected', mode === 'after')
  $('photo-n').textContent = mode === 'after' ? '3' : '2'
  $('photo-h').textContent = mode === 'after' ? 'After photo' : 'Before photo'
  label('submit', mode === 'after' ? 'Save After photo' : 'Submit kaizen')
  showMachine(); aiVisible()
}

async function scan () {
  let stream
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
  } catch { return toast('Camera not available: type the machine ID instead') }
  const dlg = $('scanner'), video = $('video')
  video.srcObject = stream
  dlg.showModal()
  await video.play()
  const detect = await detector()
  let active = true
  const stop = () => { active = false; stream.getTracks().forEach(t => t.stop()); dlg.close() }
  $('scan-cancel').onclick = stop
  dlg.oncancel = stop
  while (active) {
    const text = await detect(video).catch(() => null)
    if (text) { stop(); setEquipment(text); navigator.vibrate?.(80); break }
    await new Promise(r => setTimeout(r, 150))
  }
}
async function detector () {
  if ('BarcodeDetector' in window) { // Android Chrome: native, fast, also reads barcodes
    const d = new BarcodeDetector({ formats: ['qr_code', 'code_128', 'data_matrix'] })
    return async v => (await d.detect(v))[0]?.rawValue
  }
  if (!window.jsQR) await new Promise((ok, fail) => { // iPhone and others: QR only
    const s = document.createElement('script'); s.src = 'vendor/jsQR.js'; s.onload = ok; s.onerror = fail; document.head.append(s)
  })
  const c = document.createElement('canvas'), ctx = c.getContext('2d', { willReadFrequently: true })
  return async v => {
    if (!v.videoWidth) return null
    const scale = Math.min(1, 640 / v.videoWidth)
    c.width = v.videoWidth * scale; c.height = v.videoHeight * scale
    ctx.drawImage(v, 0, 0, c.width, c.height)
    return window.jsQR(ctx.getImageData(0, 0, c.width, c.height).data, c.width, c.height)?.data
  }
}

// ---- photos: shrink on the phone before storing (HANA BLOB in the demo) ----
async function shrink (file, max = 1600) {
  const img = await createImageBitmap(file)
  const scale = Math.min(1, max / Math.max(img.width, img.height))
  const c = document.createElement('canvas')
  c.width = Math.round(img.width * scale); c.height = Math.round(img.height * scale)
  c.getContext('2d').drawImage(img, 0, 0, c.width, c.height)
  return new Promise(ok => c.toBlob(ok, 'image/jpeg', 0.75))
}
function renderPhotos () {
  $('photos').replaceChildren(...state.photos.map(p => {
    const f = document.createElement('figure'), img = new Image(), x = document.createElement('button')
    img.src = p.url; img.alt = state.mode === 'after' ? 'After photo' : 'Before photo'
    x.type = 'button'; x.innerHTML = '<svg class="icon"><use href="#i-x"/></svg>'; x.setAttribute('aria-label', 'Remove photo')
    x.onclick = () => { URL.revokeObjectURL(p.url); state.photos = state.photos.filter(q => q !== p); renderPhotos() }
    f.append(img, x)
    return f
  }))
  label('photo-btn', state.photos.length ? 'Add another photo' : 'Take photo')
  aiVisible()
}

// ---- AI assist (online only): draft the text from the photo; warn about duplicates before submitting ----
const aiVisible = () => { $('ai-draft').hidden = !(navigator.onLine && state.mode === 'new' && state.photos.length) }
const toBase64 = blob => new Promise(ok => { const r = new FileReader(); r.onload = () => ok(r.result.split(',')[1]); r.readAsDataURL(blob) })
const knownMachine = () => { const id = $('equipment').value.trim().toUpperCase(); return state.equipment.some(e => e.ID === id) ? id : null }
async function aiDraft () {
  const btn = $('ai-draft')
  btn.disabled = true; label('ai-draft', 'Drafting…')
  try {
    const image = await toBase64(await shrink(state.photos[0].blob, 768))
    const d = await send('POST', '/draftFromPhoto', { image, equipment_ID: knownMachine(), hint: $('title').value.trim() || null })
    if (!$('title').value.trim() && d.title) $('title').value = d.title
    if (!$('problem').value.trim() && d.problem) $('problem').value = d.problem
    if (!state.pillar && d.pillar_code) $('pillars').querySelector(`[data-code="${d.pillar_code}"]`)?.click()
    if (d.isSafety) $('safety').checked = true
    state.aiDrafted = true
    $('ai-note').hidden = false; $('title-err').hidden = true
  } catch (e) {
    toast(e instanceof Retry ? 'The AI assistant is not available right now' : e.message)
  } finally { btn.disabled = false; label('ai-draft', 'Draft with AI from the photo') }
}
const literal = s => `'${encodeURIComponent((s ?? '').replace(/'/g, "''"))}'`
async function looksLikeDuplicate (title, problem, equipment) {
  if (!navigator.onLine) return false
  try {
    const [hit] = (await send('GET', `/similar(title=${literal(title)},problem=${literal(problem)},equipment_ID=${literal(equipment)})`)).value
    return hit && !confirm(`This looks like ${hit.number} "${hit.title}" (${hit.status}), already reported.\n\nSubmit anyway?`)
  } catch { return false } // the check must never block reporting
}

// ---- voice to text (Web Speech API where available; keyboard dictation otherwise) ----
function setupVoice () {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition
  if (!SR) { document.querySelectorAll('.mic').forEach(b => { b.hidden = true }); $('mic-hint').hidden = false; return }
  let rec
  document.querySelectorAll('.mic').forEach(btn => btn.onclick = () => {
    if (rec) return rec.stop()
    const field = $(btn.dataset.for)
    rec = new SR()
    rec.lang = navigator.language || 'en-US'
    rec.onresult = e => {
      const text = [...e.results].map(r => r[0].transcript).join(' ').trim()
      field.value = field.value ? `${field.value} ${text}` : text.charAt(0).toUpperCase() + text.slice(1)
      field.dispatchEvent(new Event('input'))
    }
    rec.onerror = e => toast(e.error === 'network' ? 'Voice needs network: use the keyboard microphone' : e.error === 'not-allowed' ? 'Microphone blocked' : 'Did not catch that, try again')
    rec.onend = () => { btn.classList.remove('on'); rec = null }
    btn.classList.add('on')
    rec.start()
  })
}

// ---- submit ----
async function submit (ev) {
  ev.preventDefault()
  if (state.mode === 'after') return submitAfter()
  const title = $('title').value.trim()
  const equipment = $('equipment').value.trim().toUpperCase()
  // the server derives the plant from the machine, so a queued kaizen without a known machine would fail at sync
  const machineOk = !!equipment && (!state.equipment.length || state.equipment.some(e => e.ID === equipment))
  $('equipment-err').hidden = machineOk
  $('title-err').hidden = !!title
  $('pillar-err').hidden = !!state.pillar
  if (!machineOk) return $('equipment').focus()
  if (!title) return $('title').focus()
  if (!state.pillar) return $('pillars').scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (await looksLikeDuplicate(title, $('problem').value.trim(), equipment)) return
  const benefit = parseFloat($('benefit').value)
  await queue.put({
    ID: crypto.randomUUID(),
    createdAt: Date.now(),
    data: {
      title, problem: $('problem').value.trim() || null, pillar_code: state.pillar, isSafety: $('safety').checked,
      equipment_ID: equipment || null, estimatedBenefit: Number.isFinite(benefit) ? benefit : null, aiDrafted: state.aiDrafted
    },
    photos: state.photos.map(p => ({ ID: crypto.randomUUID(), kind: 'Before', blob: p.blob }))
  })
  await afterSubmit()
}
async function submitAfter () {
  $('pick-err').hidden = !!state.pick
  $('photo-err').hidden = state.photos.length > 0
  if (!state.pick) return $('picks').scrollIntoView({ behavior: 'smooth', block: 'center' })
  if (!state.photos.length) return
  const k = state.open.find(x => x.ID === state.pick)
  await queue.put({
    ID: crypto.randomUUID(), kaizenID: k.ID, createdAt: Date.now(), data: { title: `After photo · ${k.number}` },
    photos: state.photos.map(p => ({ ID: crypto.randomUUID(), kind: 'After', blob: p.blob }))
  })
  await afterSubmit()
}
async function afterSubmit () {
  state.photos.forEach(p => URL.revokeObjectURL(p.url))
  $('form').reset(); state.photos = []; state.pillar = null; state.pick = null; state.aiDrafted = false
  $('photo-err').hidden = true; $('ai-note').hidden = true
  renderPhotos(); showMachine()
  for (const x of $('pillars').children) x.setAttribute('aria-pressed', false)
  toast(navigator.onLine ? 'Sending…' : 'Saved on phone: sends when back online')
  await render()
  scrollTo({ top: 0, behavior: 'smooth' })
  sync()
}

// ---- list + status ----
async function render () {
  const pending = (await queue.all()).sort((a, b) => b.createdAt - a.createdAt)
  const rows = [
    ...pending.map(p => ({ title: p.data.title, s: p.error ? 'error' : 'pending', label: p.error ? `Not sent: ${p.error}` : 'Waiting to send', ID: p.ID, error: p.error })),
    ...done.all().slice(0, 3).map(d => ({ title: d.title, s: 'done', label: `${d.number} · sent` }))
  ]
  $('mine-section').hidden = !rows.length
  $('mine').replaceChildren(...rows.map(r => {
    const div = document.createElement('div'), t = document.createElement('div'), s = document.createElement('div')
    div.className = 'item'; div.dataset.state = r.s
    t.className = 't'; t.textContent = r.title
    s.className = `s ${r.s}`; s.textContent = r.label
    div.append(t, s)
    if (r.error) {
      const del = document.createElement('button')
      del.type = 'button'; del.className = 'btn'; del.textContent = 'Delete'
      del.onclick = async () => { if (confirm(`Delete "${r.title}" from this phone?`)) { await queue.remove(r.ID); render() } }
      div.append(del)
    }
    return div
  }))
  const waiting = pending.filter(p => !p.error).length
  const net = $('net')
  net.classList.toggle('off', !navigator.onLine)
  net.textContent = (navigator.onLine ? 'Online' : 'Offline') + (waiting ? ` · ${waiting} waiting` : '')
}

// ---- gamification: my points, badges and rank (cached, so it shows offline too) ----
async function loadScore () {
  let s = JSON.parse(localStorage.getItem('kaizen-score') || 'null')
  if (navigator.onLine) try { s = await send('GET', '/myScore()'); localStorage.setItem('kaizen-score', JSON.stringify(s)) } catch { /* keep cached */ }
  if (!s || !s.points) return
  $('score-section').hidden = false
  $('score-points').textContent = `${s.points} points`
  $('score-rank').textContent = s.rank ? `#${s.rank} of ${s.outOf} in ${s.plantName}` : ''
  $('score-badges').replaceChildren(...s.badges.map(b => Object.assign(document.createElement('span'), { textContent: b })))
  $('score-top').replaceChildren(...s.top.map(t => Object.assign(document.createElement('li'), { textContent: `${t.name} · ${t.points}` })))
}

let toastTimer
function toast (msg) {
  const t = $('toast')
  t.textContent = msg; t.classList.add('show')
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), 3500)
}

// ---- wire up ----
$('form').addEventListener('submit', submit)
$('scan').onclick = scan
$('ai-draft').onclick = aiDraft
$('tab-new').onclick = () => setMode('new')
$('tab-after').onclick = () => setMode('after')
$('equipment').addEventListener('input', () => { $('equipment-err').hidden = true; showMachine() })
$('title').addEventListener('input', () => { if ($('title').value.trim()) $('title-err').hidden = true })
$('photo-btn').onclick = () => $('photo').click()
$('photo').onchange = async e => {
  for (const file of e.target.files) {
    const blob = await shrink(file)
    state.photos.push({ blob, url: URL.createObjectURL(blob) })
  }
  e.target.value = ''
  $('photo-err').hidden = true
  renderPhotos()
}
addEventListener('online', () => { render(); sync(); aiVisible() })
addEventListener('offline', () => { render(); aiVisible() })
document.addEventListener('visibilitychange', () => { if (!document.hidden) { sync(); loadMasterData() } })

const eq = new URLSearchParams(location.search).get('eq') // QR label link opened by the phone camera
setupVoice()
loadMasterData().then(() => { if (eq) setEquipment(eq) })
render().then(sync)
loadScore()
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js')
