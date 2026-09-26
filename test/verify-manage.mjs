// Gate script: the Maria demo is clickable end to end in the real Fiori apps (Edge on Windows, Chromium elsewhere).
// Maria captures on the phone app -> Sam approves from his inbox -> Klaus approves, starts, plans a task and a
// verified benefit -> closing is blocked by the verification gate -> Klaus adds the After photo on the phone app,
// ticks off the task and closes -> Petra sees the verified saving on the KPI page.
// Needs internet: the Fiori apps load SAPUI5 from ui5.sap.com.
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { chromium } from 'playwright'

const root = join(import.meta.dirname, '..'), PORT = 4107, BASE = `http://localhost:${PORT}`
const photo = join(root, 'app/capture/icon-512.png')
const check = (ok, msg) => { if (!ok) throw new Error(msg) }
const step = msg => console.log('  ' + msg)

const server = spawn(process.execPath, [join(root, 'node_modules/@sap/cds/bin/serve.js')],
  { cwd: root, env: { ...process.env, PORT: String(PORT), NODE_ENV: 'development' }, stdio: ['ignore', 'ignore', 'pipe'] })
let browser, failed = false
const as = async user => (await browser.newContext({ viewport: { width: 1400, height: 900 }, httpCredentials: { username: user, password: '' } })).newPage()

// Fiori helpers: header status, running an action (with or without a parameter dialog), inbox
const statusIs = (page, text) => page.locator('[id*="FieldGroup::Workflow"]').getByText(text, { exact: true }).first().waitFor({ state: 'attached', timeout: 15000 }) // header may be collapsed
async function run (page, action, fields = {}) {
  await page.getByRole('button', { name: action, exact: true }).first().click()
  if (!Object.keys(fields).length && !['Approve', 'Start', 'Close kaizen'].includes(action)) return
  const dialog = page.locator('[role=dialog]:visible').filter({ has: page.getByRole('button', { name: 'Cancel' }) }).last()
  await dialog.waitFor({ timeout: 10000 })
  for (const [label, value] of Object.entries(fields)) await dialog.getByLabel(label, { exact: false }).first().fill(value)
  await dialog.getByRole('button', { name: action.replace(' kaizen', ''), exact: false }).first().click()
}
async function openFromInbox (page, number) {
  await page.goto(`${BASE}/kaizens/index.html`)
  const row = page.getByRole('row').filter({ hasText: number }).first()
  await row.waitFor({ timeout: 30000 })
  await row.click()
  await page.getByRole('button', { name: 'Approve', exact: true }).waitFor({ timeout: 20000 })
}
async function editAndSave (page, fill) {
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).waitFor({ timeout: 15000 })
  await fill()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await page.getByRole('button', { name: 'Edit', exact: true }).waitFor({ timeout: 15000 })
}
const table = (page, nav) => page.locator(`[id*="fe::table::${nav}::LineItem"] table`).first()
// object page sections render lazily: scroll there and wait for the editable row
async function editableRow (page, nav) {
  await page.locator(`[id$="fe::FacetSection::${nav}"]`).scrollIntoViewIfNeeded()
  const row = table(page, nav).locator('tbody tr').first()
  await row.locator('input:not([type=checkbox])').first().waitFor({ timeout: 15000 })
  return row
}

try {
  for (let i = 0; ; i++) { try { await fetch(BASE); break } catch { check(i < 60, 'server did not start'); await new Promise(r => setTimeout(r, 500)) } }
  browser = await chromium.launch(process.platform === 'win32' ? { channel: 'msedge' } : {})

  // 1. Maria captures at the machine (phone app)
  const maria = await as('maria')
  await maria.goto(`${BASE}/capture/?eq=P-1042`)
  await maria.locator('#pillars button').first().waitFor()
  await maria.setInputFiles('#photo', photo)
  await maria.locator('#photos img').waitFor()
  await maria.click('#ai-draft') // AI drafts from the photo; Maria then corrects the title
  await maria.locator('#ai-note').waitFor({ timeout: 10000 })
  check((await maria.inputValue('#title')).includes('P-1042'), 'AI draft did not fill the title')
  await maria.fill('#title', 'Oil leak at pump seal')
  await maria.fill('#problem', 'Oil dripping from the shaft seal every shift')
  await maria.click('#pillars button[data-code=AM]')
  await maria.fill('#benefit', '3000')
  await maria.click('#submit')
  const sent = maria.locator('.item[data-state=done]').first()
  await sent.waitFor({ timeout: 15000 })
  const number = (await sent.textContent()).match(/KAI-\d{4}-\d{4}/)[0]
  step(`maria captured ${number} (text drafted with AI, then corrected)`)

  // 2. Sam approves from his inbox
  const sam = await as('sam')
  await openFromInbox(sam, number)
  check(await sam.getByRole('button', { name: 'Start', exact: true }).isDisabled(), 'Start should be disabled for the supervisor')
  await run(sam, 'Approve', { Note: 'Good catch' })
  await statusIs(sam, 'Under Review')
  step('sam approved from his inbox')

  // 3. Klaus approves, starts, plans a task and a verified OEE benefit
  const klaus = await as('klaus')
  await openFromInbox(klaus, number)
  await run(klaus, 'Approve')
  await statusIs(klaus, 'Approved')
  await run(klaus, 'Start', { 'Owner': 'klaus' })
  await statusIs(klaus, 'In Progress')
  await run(klaus, '✨ 5-Why analysis')
  await klaus.locator('[id$="fe::FacetSection::analysis"]').scrollIntoViewIfNeeded()
  await klaus.getByText(/^1. Why/).first().waitFor({ state: 'attached', timeout: 15000 }) // long text is shown collapsed
  await editAndSave(klaus, async () => {
    const task = (await editableRow(klaus, 'tasks')).locator('input:not([type=checkbox])')
    await task.first().fill('Replace seal on P-1042')
    await task.first().press('Tab')
    await klaus.waitForTimeout(800)
    const benefit = await editableRow(klaus, 'benefits'), b = benefit.locator('input:not([type=checkbox])')
    await b.nth(0).fill('OEE'); await b.nth(1).fill('78.2'); await b.nth(2).fill('83.5'); await b.nth(3).fill('% OEE'); await b.nth(4).fill('14200')
    await benefit.getByRole('checkbox').last().check()
    await klaus.waitForTimeout(800)
  })
  await table(klaus, 'tasks').getByText('Replace seal on P-1042').waitFor({ timeout: 10000 })
  await table(klaus, 'benefits').getByText('14,200.00').waitFor({ timeout: 10000 })
  step('klaus approved, started, ran the 5-Why analysis, added a task and a verified 14,200 EUR OEE benefit')

  // 4. verification gate blocks closing
  await run(klaus, 'Request verification')
  await statusIs(klaus, 'Verification')
  await run(klaus, 'Close kaizen')
  const gate = klaus.getByText(/Verification gate: needs at least one After photo, 1 open task/)
  await gate.first().waitFor({ timeout: 10000 })
  await klaus.getByRole('button', { name: 'Close', exact: true }).last().click()
  step('closing blocked: no After photo, open task')

  // 5. Klaus adds the After photo on the phone app, ticks off the task, closes
  const phone = await as('klaus')
  await phone.goto(`${BASE}/capture/`)
  await phone.locator('#pillars button').first().waitFor()
  await phone.click('#tab-after')
  await phone.fill('#equipment', 'P-1042')
  await phone.locator('#picks button', { hasText: number }).click()
  await phone.setInputFiles('#photo', photo)
  await phone.locator('#photos img').waitFor()
  await phone.click('#submit')
  await phone.locator('.item[data-state=done]', { hasText: 'After photo' }).waitFor({ timeout: 15000 })
  await klaus.reload()
  await klaus.getByRole('button', { name: 'Edit', exact: true }).waitFor({ timeout: 20000 })
  await editAndSave(klaus, async () => {
    // the empty "new row" sits on top in edit mode: tick the row that holds the existing task
    await editableRow(klaus, 'tasks')
    const rows = table(klaus, 'tasks').locator('tbody tr')
    let taskRow
    for (let i = 0; i < await rows.count() && !taskRow; i++)
      if (await rows.nth(i).locator('input:not([type=checkbox])').first().inputValue().catch(() => '') === 'Replace seal on P-1042') taskRow = rows.nth(i)
    check(taskRow, 'existing task row not found in edit mode')
    await taskRow.getByRole('checkbox').last().check()
    await klaus.waitForTimeout(800)
  })
  await run(klaus, 'Close kaizen', { Note: 'OEE 78.2% -> 83.5%' })
  await statusIs(klaus, 'Closed')
  check(await klaus.getByText('Before', { exact: true }).count() > 0 && await klaus.getByText('After', { exact: true }).count() > 0, 'Before and After photos not both shown')
  step('klaus added the After photo, finished the task and closed the kaizen')

  // A3 report: generated from the kaizen data, printable with Before and After photos
  await run(klaus, '✨ Generate A3')
  await klaus.waitForTimeout(1500)
  const id = decodeURIComponent(klaus.url()).match(/ID=([0-9a-f-]{36})/)[1]
  await klaus.goto(`${BASE}/a3/index.html?ID=${id}`)
  await klaus.getByText('1 · Background').waitFor({ timeout: 15000 })
  await klaus.waitForFunction(() => [...document.images].filter(i => i.complete && i.naturalWidth > 0).length === 2, null, { timeout: 15000 })
  check((await klaus.textContent('body')).includes('14,200'), 'A3 results do not show the verified saving')
  step('klaus generated the A3 report with 5-Why, Before/After photos and the 14,200 EUR result')

  // 6. Petra sees the verified saving
  const petra = await as('petra')
  await petra.goto(`${BASE}/kpis/index.html`)
  const hh = petra.getByRole('row').filter({ hasText: 'Plant Hamburg' }).filter({ hasText: 'Autonomous Maintenance' }).first()
  await hh.waitFor({ timeout: 30000 })
  check((await hh.textContent()).includes('14,200.00'), `KPI row does not show the verified saving: ${await hh.textContent()}`)
  step('petra sees 14,200 EUR verified for Hamburg / Autonomous Maintenance')

  // 7. gamification on the phone: reported 10 + approved 10 + closed 30 + 14 x 1,000 EUR verified = 64 points
  await maria.goto(`${BASE}/capture/`)
  await maria.locator('#score-points', { hasText: '⭐ 64 points' }).waitFor({ timeout: 15000 })
  check((await maria.textContent('#score-badges')).includes('10k saver'), 'badge missing on the phone')
  step('maria sees 64 points and her badges on the phone')

  // 8. horizontal deployment: Klaus copies the proven kaizen to a Munich machine
  await klaus.goto(`${BASE}/kaizens/index.html#/Kaizens(ID=${id},IsActiveEntity=true)`)
  await klaus.getByRole('button', { name: 'Deploy to another machine', exact: true }).waitFor({ timeout: 20000 })
  await run(klaus, 'Deploy to another machine', { 'Target machine': 'P-2042' })
  await klaus.getByText(/Created KAI-\d{4}-\d{4} for machine P-2042/).first().waitFor({ state: 'attached', timeout: 15000 })
  step('klaus deployed the closed kaizen to machine P-2042 (new kaizen, normal approval)')

  // 9. leaderboard: Maria on top with the Teacher badge for the deployment
  await petra.goto(`${BASE}/leaderboard/index.html`)
  const top = petra.getByRole('row').filter({ hasText: 'maria' }).first()
  await top.waitFor({ timeout: 30000 })
  check((await top.textContent()).includes('Teacher'), `leaderboard row lacks the Teacher badge: ${await top.textContent()}`)
  step('petra sees the leaderboard: maria first, with the Teacher badge')

  // operators cannot open the manager app's data
  const denied = await (await maria.request.get(`${BASE}/odata/v4/manage/Kaizens`)).status()
  check(denied === 403, `operator got ${denied} from the manager service`)
  console.log('maria scenario clickable')
} catch (e) {
  failed = true
  const pages = (browser?.contexts() ?? []).flatMap(c => c.pages())
  for (const [i, p] of pages.entries()) await p.screenshot({ path: join(process.env.TMP ?? root, `verify-manage-fail-${i}.png`) }).catch(() => {})
  for (const [i, p] of (browser?.contexts() ?? []).flatMap(c => c.pages()).entries()) await p.screenshot({ path: join(process.env.TMP ?? '.', ) }).catch(() => {})
  console.error(e.message.split('\n')[0])
} finally {
  await browser?.close()
  server.kill()
  process.exit(failed ? 1 : 0)
}
