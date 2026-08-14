#!/usr/bin/env node

import { writeFileSync } from 'node:fs'

const endpoint = process.env.DSH_CDP_ENDPOINT ?? 'http://127.0.0.1:9222'
const screenshotPath = process.argv[2]

const targets = await fetch(`${endpoint}/json/list`).then((response) => {
  if (!response.ok) throw new Error(`CDP target list returned HTTP ${response.status}`)
  return response.json()
})
const target = targets.find((entry) => entry.type === 'page' && entry.url.startsWith('http'))
if (!target?.webSocketDebuggerUrl) throw new Error('No debuggable Harness WebView page found')
console.error(`target ${target.title} ${target.url}`)

const socket = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true })
  socket.addEventListener('error', reject, { once: true })
})
console.error('cdp connected')

let sequence = 0
const pending = new Map()
socket.addEventListener('message', (event) => {
  const message = JSON.parse(event.data)
  if (!message.id) return
  const waiter = pending.get(message.id)
  if (!waiter) return
  pending.delete(message.id)
  if (message.error) waiter.reject(new Error(message.error.message))
  else waiter.resolve(message.result)
})

function command(method, params = {}) {
  const id = ++sequence
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject })
    socket.send(JSON.stringify({ id, method, params }))
  })
}

async function evaluate(expression) {
  const result = await command('Runtime.evaluate', {
    expression,
    awaitPromise: true,
    returnByValue: true,
  })
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
  return result.result.value
}

function timeout(promise, milliseconds, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} timed out`)), milliseconds)),
  ])
}

await command('Runtime.enable')
console.error('runtime enabled')
await command('Page.enable')
console.error('page enabled')

for (let attempt = 0; attempt < 40; attempt += 1) {
  const ready = await evaluate(`document.readyState === 'complete'
    && document.body.hasAttribute('data-dsh-android')
    && Boolean(document.querySelector('[data-shell-overlay]'))`)
  if (ready) break
  if (attempt === 39) throw new Error('Harness WebView did not become ready within 20 seconds')
  await new Promise((resolve) => setTimeout(resolve, 500))
}

const initialWidth = await evaluate('innerWidth')
let emulated = false
if (initialWidth === 0 || process.env.DSH_EMULATE === '1') {
  await command('Emulation.setDeviceMetricsOverride', {
    width: 360,
    height: 592,
    deviceScaleFactor: 3,
    mobile: true,
  })
  await new Promise((resolve) => setTimeout(resolve, 500))
  emulated = true
  console.error('mobile viewport emulated')
}

const report = await evaluate(`(() => {
  const resources = performance.getEntriesByType('resource')
  const buttons = [...document.querySelectorAll('button')].map((button) => ({
    text: button.innerText.trim().replace(/\\s+/g, ' ').slice(0, 80),
    label: button.getAttribute('aria-label') || button.getAttribute('title') || '',
    disabled: button.disabled,
  })).filter((button) => button.text || button.label)
  return {
    title: document.title,
    url: location.href,
    readyState: document.readyState,
    viewport: { width: innerWidth, height: innerHeight, dpr: devicePixelRatio },
    mobileMarker: document.body.hasAttribute('data-dsh-android'),
    mobileStyle: Boolean(document.getElementById('dsh-android-mobile')),
    rootChildren: document.getElementById('root')?.childElementCount ?? 0,
    visibleText: document.body.innerText.trim().replace(/\\s+/g, ' ').slice(0, 800),
    buttons: buttons.slice(0, 50),
    localStorageKeys: Object.keys(localStorage).sort(),
    resourceCount: resources.length,
    pluginResources: resources.filter((entry) => entry.name.includes('/plugins/')).length,
    failedResources: resources.filter((entry) => entry.duration === 0 && entry.transferSize === 0).map((entry) => entry.name).slice(0, 20),
    shellOverlay: Boolean(document.querySelector('[data-shell-overlay]')),
    settingsDebug: (() => {
      const close = [...document.querySelectorAll('button')].find((item) => item.innerText.trim() === '关闭')
      if (!close) return null
      const ancestors = []
      let element = close
      while (element && ancestors.length < 8) {
        const rect = element.getBoundingClientRect()
        ancestors.push({
          tag: element.tagName,
          className: typeof element.className === 'string' ? element.className : '',
          role: element.getAttribute('role') || '',
          width: rect.width,
          height: rect.height,
        })
        element = element.parentElement
      }
      return ancestors
    })(),
    frame: (() => {
      const overlay = document.querySelector('[data-shell-overlay]')
      const frame = overlay?.parentElement
      if (!frame) return null
      const rect = frame.getBoundingClientRect()
      return {
        width: rect.width,
        height: rect.height,
        columns: getComputedStyle(frame).gridTemplateColumns,
        sidebarCollapsed: frame.hasAttribute('data-sidebar-collapsed'),
      }
    })(),
  }
})()`)
console.error('page evaluated')

if (process.env.DSH_INTERACTIVE === '1') {
  await evaluate(`(() => {
    const close = [...document.querySelectorAll('button')].find((item) =>
      (item.getAttribute('aria-label') || '').includes('关闭设置')
        || item.innerText.trim() === '关闭')
    close?.click()
  })()`)
  await new Promise((resolve) => setTimeout(resolve, 250))
  await evaluate(`(() => {
    if ([...document.querySelectorAll('button')].some((item) => item.innerText.trim() === '设置')) return false
    const button = [...document.querySelectorAll('button')].find((item) =>
      (item.getAttribute('aria-label') || '').includes('打开侧边栏'))
    button?.click()
    return Boolean(button)
  })()`)
  await new Promise((resolve) => setTimeout(resolve, 500))
  report.expandedButtons = await evaluate(`([...document.querySelectorAll('button')]
    .map((button) => ({ text: button.innerText.trim().replace(/\\s+/g, ' '), label: button.getAttribute('aria-label') || '' }))
    .filter((button) => button.text || button.label)
    .slice(0, 60))`)
  const openedSettings = await evaluate(`(() => {
    const button = [...document.querySelectorAll('button')].find((item) =>
      item.innerText.trim() === '设置' || (item.getAttribute('aria-label') || '').includes('设置'))
    button?.click()
    return Boolean(button)
  })()`)
  await new Promise((resolve) => setTimeout(resolve, 500))
  report.settings = await evaluate(`(() => {
    const overlay = document.querySelector('[role="presentation"][class*="_overlay"]')
    const panel = document.querySelector('[role="dialog"][class*="_panel"]')
    if (!panel) return {
      opened: ${openedSettings},
      panel: false,
      text: overlay?.innerText.trim().replace(/\\s+/g, ' ').slice(0, 300) ?? '',
      classes: [...(overlay?.querySelectorAll('*') ?? [])]
        .map((element) => typeof element.className === 'string' ? element.className : '')
        .filter(Boolean).slice(0, 80),
    }
    const rect = panel.getBoundingClientRect()
    const nav = panel.querySelector('[class*="_nav"]')
    return {
      opened: ${openedSettings},
      panel: true,
      width: rect.width,
      height: rect.height,
      flexDirection: getComputedStyle(panel).flexDirection,
      navWidth: nav?.getBoundingClientRect().width ?? null,
      navDirection: nav ? getComputedStyle(nav).flexDirection : null,
    }
  })()`)
  await evaluate(`(() => {
    const close = [...document.querySelectorAll('button')].find((item) => item.innerText.trim() === '关闭')
    close?.click()
    const collapse = [...document.querySelectorAll('button')].find((item) =>
      (item.getAttribute('aria-label') || '').includes('收起侧边栏'))
    collapse?.click()
  })()`)
}

if (screenshotPath) {
  try {
    const screenshot = await timeout(command('Page.captureScreenshot', {
      format: 'png',
      fromSurface: false,
      captureBeyondViewport: false,
    }), 3000, 'screenshot')
    writeFileSync(screenshotPath, Buffer.from(screenshot.data, 'base64'))
    report.screenshot = screenshotPath
    console.error('screenshot captured')
  } catch (error) {
    report.screenshotError = error.message
  }
}

console.log(JSON.stringify(report, null, 2))
if (emulated) await command('Emulation.clearDeviceMetricsOverride')
socket.close()
