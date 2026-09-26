// Gate script: the capture PWA works offline and syncs. Real browser (Edge on Windows, Chromium elsewhere),
// real CAP server: load app, go offline, reload from the service worker, submit with a photo, reconnect,
// then check on the server that the kaizen and its photo bytes arrived exactly once.
import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { chromium } from 'playwright'

const root = join(import.meta.dirname, '..'), PORT = 4106, BASE = `http://localhost:${PORT}`
const auth = { authorization: 'Basic ' + Buffer.from('sam:').toString('base64') }
const api = path => fetch(`${BASE}/odata/v4/kaizen/${path}`, { headers: auth })
const check = (ok, msg) => { if (!ok) throw new Error(msg) }

const server = spawn(process.execPath, [join(root, 'node_modules/@sap/cds/bin/serve.js')],
  { cwd: root, env: { ...process.env, PORT: String(PORT), NODE_ENV: 'development' }, stdio: ['ignore', 'ignore', 'pipe'] })
let browser, failed = false
try {
  for (let i = 0; ; i++) { try { await fetch(BASE); break } catch { check(i < 60, 'server did not start'); await new Promise(r => setTimeout(r, 500)) } }
  browser = await chromium.launch(process.platform === 'win32' ? { channel: 'msedge' } : {})
  const ctx = await browser.newContext({ httpCredentials: { username: 'maria', password: '' } })
  const page = await ctx.newPage()

  // installable PWA: manifest + service worker controlling the page
  await page.goto(`${BASE}/capture/`)
  check(await page.evaluate(() => Promise.race([navigator.serviceWorker.ready.then(() => true), new Promise(r => setTimeout(r, 10000, false))])),
    'service worker did not activate')
  await page.reload()
  await page.locator('#pillars button').first().waitFor()
  const pwa = await page.evaluate(async () => {
    const m = await (await fetch(document.querySelector('link[rel=manifest]').href)).json()
    return { controlled: !!navigator.serviceWorker.controller, display: m.display, start: m.start_url, sizes: m.icons.map(i => i.sizes) }
  })
  check(pwa.controlled, 'service worker does not control the page')
  check(pwa.display === 'standalone' && pwa.start && pwa.sizes.includes('192x192') && pwa.sizes.includes('512x512'), `manifest not installable: ${JSON.stringify(pwa)}`)

  // offline: app still opens and accepts a kaizen
  await ctx.setOffline(true)
  await page.reload()
  await page.locator('#pillars button').first().waitFor()
  check((await page.textContent('#net')).startsWith('Offline'), 'app does not show offline state')
  await page.fill('#title', 'No machine given')
  await page.click('#pillars button[data-code=AM]')
  await page.click('#submit')
  check(await page.isVisible('#equipment-err'), 'kaizen without a machine was not stopped')
  await page.waitForTimeout(500) // a wrongly queued kaizen needs a moment to appear
  check(await page.locator('.item[data-state=pending]').count() === 0, 'kaizen without a machine was queued (would fail at sync)')
  await page.fill('#equipment', 'p-1042')
  check((await page.textContent('#machine')).includes('Plant Hamburg'), 'machine not resolved from offline cache')
  await page.setInputFiles('#photo', join(root, 'app/capture/icon-512.png'))
  await page.locator('#photos img').waitFor()
  const title = `Offline gate ${Date.now()}`
  await page.fill('#title', title)
  await page.click('#pillars button[data-code=AM]')
  await page.click('#submit')
  await page.locator('.item[data-state=pending]').waitFor()
  check((await page.textContent('#net')).includes('1 waiting'), 'queued kaizen not counted')
  const before = (await (await api(`Kaizens?$filter=title eq '${title}'`)).json()).value
  check(before.length === 0, 'kaizen reached the server while offline')

  // back online: queue drains, server has the kaizen once, with its photo
  await ctx.setOffline(false)
  await page.locator('.item[data-state=done]').waitFor({ timeout: 15000 })
  const after = (await (await api(`Kaizens?$filter=title eq '${title}'&$expand=photos`)).json()).value
  check(after.length === 1, `expected exactly 1 synced kaizen, got ${after.length}`)
  const k = after[0]
  check(k.plant_ID === 'DE_HH' && k.equipment_ID === 'P-1042' && k.status_code === 'Submitted', `wrong kaizen data: ${JSON.stringify(k)}`)
  check((await page.textContent('#mine')).includes(k.number), 'phone does not show the kaizen number')
  check(k.photos.length === 1 && k.photos[0].kind === 'Before', 'Before photo missing')
  const img = Buffer.from(await (await api(`Photos(${k.photos[0].ID})/content`)).arrayBuffer())
  check(img.length > 1000 && img[0] === 0xff && img[1] === 0xd8, `photo is not a JPEG (${img.length} bytes)`)
  check(!(await page.textContent('#net')).includes('waiting'), 'queue not empty after sync')

  // After photo: once the kaizen is approved and started, the owner attaches an After photo at the machine
  for (const [user, action, body] of [['sam', 'approve'], ['klaus', 'approve'], ['klaus', 'start', { owner: 'klaus' }]]) {
    const r = await fetch(`${BASE}/odata/v4/kaizen/Kaizens(${k.ID})/KaizenService.${action}`, {
      method: 'POST', headers: { authorization: 'Basic ' + Buffer.from(user + ':').toString('base64'), 'content-type': 'application/json' }, body: JSON.stringify(body ?? {})
    })
    check(r.ok, `${user} ${action} failed: ${r.status}`)
  }
  await page.reload()
  await page.click('#tab-after')
  await page.fill('#equipment', 'P-1042')
  await page.locator('#picks button', { hasText: k.number }).click()
  await page.setInputFiles('#photo', join(root, 'app/capture/icon-192.png'))
  await page.locator('#photos img').waitFor()
  await page.click('#submit')
  await page.locator('.item[data-state=done]', { hasText: 'After photo' }).waitFor({ timeout: 15000 })
  const kinds = (await (await api(`Photos?$filter=kaizen_ID eq ${k.ID}&$select=kind`)).json()).value.map(p => p.kind).sort()
  check(JSON.stringify(kinds) === '["After","Before"]', `expected Before + After photos, got ${JSON.stringify(kinds)}`)
  console.log('offline capture verified')
} catch (e) {
  failed = true
  console.error(e.message)
} finally {
  await browser?.close()
  server.kill()
  process.exit(failed ? 1 : 0)
}
