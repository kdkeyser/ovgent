#!/usr/bin/env node
// Admin server for pipeline data compilation UI
// Run from the repo root: node scripts/admin-server.js
// Then open http://localhost:3001

const http = require('http')
const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')

const PORT = 3001
const ROOT = path.join(__dirname, '..')
const GTFS_DIR = path.join(ROOT, 'data/gtfs')
const GTFS_URL = 'https://gtfs.irail.be/de-lijn/de_lijn-gtfs.zip'
const GTFS_ZIP = path.join(GTFS_DIR, 'de_lijn-gtfs.zip')

let activeProcess = null
let sseClients = []

// ── ISO week helpers ──────────────────────────────────────────────────────────

function isoWeekFromDate(date) {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()))
  const dayNum = d.getUTCDay() || 7
  d.setUTCDate(d.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1))
  const week = Math.ceil((((d - yearStart) / 86400000) + 1) / 7)
  return { year: d.getUTCFullYear(), week }
}

function weeksBetween(startDateStr, endDateStr) {
  const start = new Date(startDateStr)
  const end = new Date(endDateStr)
  const weeks = []
  const cur = new Date(start)
  const day = cur.getDay() || 7
  cur.setDate(cur.getDate() - (day - 1))
  while (cur <= end) {
    const { year, week } = isoWeekFromDate(cur)
    weeks.push(`${year}-W${String(week).padStart(2, '0')}`)
    cur.setDate(cur.getDate() + 7)
  }
  return [...new Set(weeks)]
}

// Monday of a given ISO week string, e.g. "2026-W23" → Date
function mondayOfWeek(weekStr) {
  const [yearStr, wStr] = weekStr.split('-W')
  const year = parseInt(yearStr), week = parseInt(wStr)
  const jan4 = new Date(Date.UTC(year, 0, 4))
  const dow = jan4.getUTCDay() || 7
  const monday = new Date(jan4)
  monday.setUTCDate(jan4.getUTCDate() - (dow - 1) + (week - 1) * 7)
  return monday
}

function sundayOfWeek(weekStr) {
  const mon = mondayOfWeek(weekStr)
  const sun = new Date(mon)
  sun.setUTCDate(mon.getUTCDate() + 6)
  return sun
}

function dateToYYYYMMDD(d) {
  return d.toISOString().slice(0, 10).replace(/-/g, '')
}

// ── GTFS coverage check ───────────────────────────────────────────────────────

function getGtfsCoverage() {
  const feedInfoPath = path.join(GTFS_DIR, 'feed_info.txt')
  const calPath = path.join(GTFS_DIR, 'calendar_dates.txt')

  if (!fs.existsSync(feedInfoPath) || !fs.existsSync(calPath)) {
    return { available: false }
  }

  // Parse feed_info.txt
  const feedLines = fs.readFileSync(feedInfoPath, 'utf8').trim().split('\n')
  const headers = feedLines[0].split(',').map(h => h.trim().replace(/^"|"$/g, ''))
  const values = feedLines[1].split(',').map(v => v.trim().replace(/^"|"$/g, ''))
  const feed = Object.fromEntries(headers.map((h, i) => [h, values[i]]))

  // Parse calendar_dates.txt for the set of covered dates
  const calLines = fs.readFileSync(calPath, 'utf8').trim().split('\n').slice(1)
  const coveredDates = new Set(calLines.map(l => l.split(',')[1].trim().replace(/^"|"$/g, '')))

  return {
    available: true,
    feedStart: feed.feed_start_date,
    feedEnd: feed.feed_end_date,
    feedVersion: feed.feed_version,
    coveredDates,
  }
}

function checkWeeksCoverage(weeks) {
  const cov = getGtfsCoverage()
  if (!cov.available) {
    return weeks.map(w => ({ week: w, covered: false, reason: 'No GTFS data' }))
  }

  return weeks.map(w => {
    const mon = dateToYYYYMMDD(mondayOfWeek(w))
    const sun = dateToYYYYMMDD(sundayOfWeek(w))
    // A week is "covered" if at least one weekday in the week has service data
    const hasSomeDay = [0,1,2,3,4,5,6].every(offset => {
      const d = new Date(mondayOfWeek(w))
      d.setUTCDate(d.getUTCDate() + offset)
      return cov.coveredDates.has(dateToYYYYMMDD(d))
    })
    return {
      week: w,
      covered: hasSomeDay,
      reason: hasSomeDay ? null : `No service dates in GTFS (feed covers ${cov.feedStart}–${cov.feedEnd})`,
    }
  })
}

// ── SSE broadcast ─────────────────────────────────────────────────────────────

function broadcast(type, data) {
  const msg = `data: ${JSON.stringify({ type, data })}\n\n`
  sseClients.forEach(res => res.write(msg))
}

// ── Pipeline runner ───────────────────────────────────────────────────────────

function cleanManifest() {
  const manifestPath = path.join(ROOT, 'frontend/public/weeks-manifest.json')
  if (!fs.existsSync(manifestPath)) return
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  const before = manifest.weeks.length
  manifest.weeks = manifest.weeks.filter(entry => {
    const geojsonPath = path.join(ROOT, `frontend/public/ghent-hexes-${entry.id}.geojson`)
    return fs.existsSync(geojsonPath)
  })
  const removed = before - manifest.weeks.length
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2))
  if (removed > 0) {
    broadcast('log', `\nCleaned manifest: removed ${removed} stale ${removed === 1 ? 'entry' : 'entries'} (${manifest.weeks.length} weeks remain)\n`)
  }
}

function spawnWithOutput(cmd, label, onSuccess) {
  if (activeProcess) return { ok: false, error: 'A process is already running.' }

  broadcast('start', { label })
  broadcast('log', `▶ ${cmd}\n\n`)

  activeProcess = spawn('bash', ['-c', cmd], { cwd: ROOT })

  activeProcess.stdout.on('data', d => broadcast('log', d.toString()))
  activeProcess.stderr.on('data', d => broadcast('log', d.toString()))

  activeProcess.on('close', code => {
    if (code === 0 && onSuccess) onSuccess()
    broadcast('done', { code, success: code === 0 })
    broadcast('log', `\nProcess exited with code ${code}\n`)
    activeProcess = null
  })

  return { ok: true }
}

function runPipeline(weeks) {
  const weekArg = weeks.join(',')
  const cmd = `./gradlew pipeline:run "--args=--weeks ${weekArg}"`
  return spawnWithOutput(cmd, `Pipeline: ${weekArg}`, cleanManifest)
}

function downloadGtfs() {
  // Delete extracted files to force re-extraction; keep zip only if we're about to overwrite it anyway
  const toDelete = ['stops.txt','trips.txt','stop_times.txt','routes.txt','calendar_dates.txt',
                    'agency.txt','feed_info.txt','transfers.txt','shapes.txt','areas.txt',
                    'stop_areas.txt', 'de_lijn-gtfs.zip']
  toDelete.forEach(f => {
    const fp = path.join(GTFS_DIR, f)
    if (fs.existsSync(fp)) fs.unlinkSync(fp)
  })

  fs.mkdirSync(GTFS_DIR, { recursive: true })

  const cmd = [
    `echo "Downloading GTFS from ${GTFS_URL}..."`,
    `curl -L --progress-bar -o "${GTFS_ZIP}" "${GTFS_URL}"`,
    `echo "Extracting..."`,
    `unzip -q -o "${GTFS_ZIP}" -d "${GTFS_DIR}"`,
    `echo "Done. Feed info:"`,
    `cat "${path.join(GTFS_DIR, 'feed_info.txt')}"`,
  ].join(' && ')

  return spawnWithOutput(cmd, 'Download GTFS')
}

function stopProcess() {
  if (!activeProcess) return { ok: false, error: 'No process is running.' }
  activeProcess.kill('SIGTERM')
  return { ok: true }
}

// ── HTTP server ───────────────────────────────────────────────────────────────

const HTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <title>Pipeline Admin</title>
  <style>
    *, *::before, *::after { box-sizing: border-box; margin: 0; padding: 0; }
    body { background: #0f172a; color: #f1f5f9; font-family: system-ui, sans-serif; font-size: 14px; padding: 24px; max-width: 900px; }
    h1 { font-size: 16px; font-weight: 700; letter-spacing: .07em; text-transform: uppercase; color: #94a3b8; margin-bottom: 24px; }
    h2 { font-size: 11px; font-weight: 700; letter-spacing: .07em; text-transform: uppercase; color: #64748b; margin-bottom: 8px; }
    .card { background: #1e293b; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
    .row { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
    label { display: block; margin-bottom: 4px; font-size: 11px; color: #94a3b8; text-transform: uppercase; letter-spacing: .06em; }
    input[type=date] {
      background: #0f172a; color: #f1f5f9; border: 1px solid #334155;
      border-radius: 4px; padding: 7px 10px; font-size: 13px;
    }
    input[type=date]::-webkit-calendar-picker-indicator { filter: invert(1) opacity(.5); cursor: pointer; }
    .btn { padding: 8px 16px; border-radius: 4px; border: none; cursor: pointer; font-size: 13px; font-weight: 600; transition: opacity .15s; }
    .btn:disabled { opacity: .4; cursor: default; }
    .btn-primary  { background: #2563eb; color: #fff; }
    .btn-primary:not(:disabled):hover  { background: #1d4ed8; }
    .btn-secondary { background: #334155; color: #94a3b8; }
    .btn-secondary:not(:disabled):hover { background: #475569; color: #f1f5f9; }
    .btn-danger  { background: #dc2626; color: #fff; }
    .btn-danger:not(:disabled):hover  { background: #b91c1c; }
    .btn-warning { background: #d97706; color: #fff; }
    .btn-warning:not(:disabled):hover { background: #b45309; }
    .week-grid { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px; }
    .week-chip {
      display: flex; align-items: center; gap: 5px;
      background: #0f172a; border: 1px solid #334155; border-radius: 20px;
      padding: 4px 10px 4px 8px; font-size: 12px; cursor: pointer; user-select: none;
    }
    .week-chip.selected   { background: #1e3a8a; border-color: #2563eb; color: #bfdbfe; }
    .week-chip.uncovered  { border-color: #b45309; color: #fcd34d; }
    .week-chip.uncovered.selected { background: #451a03; border-color: #d97706; }
    .week-chip input { display: none; }
    .week-chip .cov-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
    .week-chip.selected   .cov-dot { background: #60a5fa; }
    .week-chip.uncovered  .cov-dot { background: #f59e0b; }
    .console { background: #020617; border: 1px solid #1e293b; border-radius: 6px; padding: 12px; font-family: 'Courier New', monospace; font-size: 12px; height: 400px; overflow-y: auto; white-space: pre-wrap; word-break: break-all; color: #94a3b8; }
    .status-bar { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-radius: 4px; margin-bottom: 12px; font-size: 12px; background: #0f172a; }
    .dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
    .dot.idle    { background: #475569; }
    .dot.running { background: #22c55e; animation: pulse 1s infinite; }
    .dot.done    { background: #2563eb; }
    .dot.error   { background: #ef4444; }
    @keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.4} }
    .sel-btns { display: flex; gap: 6px; margin-bottom: 10px; flex-wrap: wrap; align-items: center; }
    .count { font-size: 12px; color: #64748b; }
    .banner { border-radius: 6px; padding: 10px 14px; margin-bottom: 12px; font-size: 13px; display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .banner.warn { background: #451a03; border: 1px solid #b45309; color: #fcd34d; }
    .banner.info { background: #0c1a2e; border: 1px solid #1e3a8a; color: #93c5fd; }
    .gtfs-info { font-size: 12px; color: #64748b; }
    #gtfs-status { font-size: 12px; color: #64748b; padding: 8px 0; }
  </style>
</head>
<body>
  <h1>Pipeline Admin — Data Compilation</h1>

  <div class="card">
    <h2>GTFS data</h2>
    <div id="gtfs-status">Checking…</div>
    <div style="margin-top:10px">
      <button class="btn btn-warning" id="dl-btn" onclick="downloadGtfs()">Download latest GTFS</button>
    </div>
  </div>

  <div class="card">
    <h2>Date range</h2>
    <div class="row" style="margin-bottom:12px">
      <div>
        <label for="start">Start date</label>
        <input type="date" id="start" value="2026-06-01"/>
      </div>
      <div>
        <label for="end">End date</label>
        <input type="date" id="end" value="2026-09-28"/>
      </div>
      <button class="btn btn-secondary" onclick="computeWeeks()">Calculate weeks</button>
    </div>

    <div id="coverage-banner" style="display:none"></div>

    <h2>Weeks to process</h2>
    <div class="sel-btns">
      <button class="btn btn-secondary" onclick="selectAll()">Select all</button>
      <button class="btn btn-secondary" onclick="selectNone()">Select none</button>
      <button class="btn btn-secondary" onclick="selectCovered()">Covered only</button>
      <span class="count" id="count">0 selected</span>
    </div>
    <div class="week-grid" id="week-grid"></div>
  </div>

  <div class="card">
    <div class="status-bar">
      <div class="dot idle" id="dot"></div>
      <span id="status-text">Idle</span>
    </div>
    <div class="row" style="margin-bottom:12px">
      <button class="btn btn-primary" id="run-btn" onclick="runPipeline()">Run pipeline</button>
      <button class="btn btn-danger"  id="stop-btn" disabled onclick="stopProcess()">Stop</button>
      <button class="btn btn-secondary" onclick="clearConsole()">Clear</button>
    </div>
    <div class="console" id="console"></div>
  </div>

  <script>
    let weekCoverage = []
    let running = false

    // ── GTFS status ──────────────────────────────────────────────────────────
    function loadGtfsStatus() {
      fetch('/api/gtfs-coverage')
        .then(r => r.json())
        .then(d => {
          const el = document.getElementById('gtfs-status')
          if (!d.available) {
            el.innerHTML = '<span style="color:#f87171">No GTFS data found.</span> Download it first.'
          } else {
            const s = d.feedStart, e = d.feedEnd
            el.innerHTML = \`Feed <strong>\${d.feedVersion || 'unknown'}</strong> &mdash; covers \${fmt(s)} to \${fmt(e)}\`
          }
        })
    }

    function fmt(yyyymmdd) {
      if (!yyyymmdd || yyyymmdd.length !== 8) return yyyymmdd
      return yyyymmdd.slice(6,8) + '/' + yyyymmdd.slice(4,6) + '/' + yyyymmdd.slice(0,4)
    }

    // ── Week selection ───────────────────────────────────────────────────────
    function computeWeeks() {
      const start = document.getElementById('start').value
      const end   = document.getElementById('end').value
      if (!start || !end || start > end) { alert('Invalid date range'); return }
      fetch('/api/weeks-coverage?' + new URLSearchParams({ start, end }))
        .then(r => r.json())
        .then(data => {
          weekCoverage = data.weeks
          renderWeeks()
        })
    }

    function renderWeeks() {
      const grid = document.getElementById('week-grid')
      grid.innerHTML = ''
      const uncoveredCount = weekCoverage.filter(w => !w.covered).length

      weekCoverage.forEach(w => {
        const chip = document.createElement('label')
        chip.className = 'week-chip selected' + (w.covered ? '' : ' uncovered')
        chip.dataset.week = w.week
        chip.dataset.covered = w.covered ? '1' : '0'
        chip.title = w.reason || 'Covered by GTFS'
        chip.innerHTML = \`<input type="checkbox" checked/><span class="cov-dot"></span>\${w.week}\`
        chip.querySelector('input').addEventListener('change', e => {
          chip.classList.toggle('selected', e.target.checked)
          updateCount()
        })
        grid.appendChild(chip)
      })
      updateCount()

      const banner = document.getElementById('coverage-banner')
      if (uncoveredCount > 0) {
        banner.style.display = ''
        banner.className = 'banner warn'
        banner.innerHTML = \`
          <span>⚠ <strong>\${uncoveredCount} week\${uncoveredCount>1?'s':''}</strong> not covered by the current GTFS feed.</span>
          <button class="btn btn-warning" onclick="downloadAndRefresh()">Download latest GTFS & retry</button>
        \`
      } else {
        banner.style.display = 'none'
      }
    }

    function selectAll()     { document.querySelectorAll('.week-chip input').forEach(i => { i.checked = true;  i.closest('.week-chip').classList.add('selected')    }); updateCount() }
    function selectNone()    { document.querySelectorAll('.week-chip input').forEach(i => { i.checked = false; i.closest('.week-chip').classList.remove('selected') }); updateCount() }
    function selectCovered() { document.querySelectorAll('.week-chip').forEach(chip => { const ok = chip.dataset.covered === '1'; chip.querySelector('input').checked = ok; chip.classList.toggle('selected', ok) }); updateCount() }

    function updateCount() {
      const n = document.querySelectorAll('.week-chip input:checked').length
      document.getElementById('count').textContent = n + ' selected'
    }

    function selectedWeeks() {
      return [...document.querySelectorAll('.week-chip input:checked')].map(i => i.closest('.week-chip').dataset.week)
    }

    // ── Actions ──────────────────────────────────────────────────────────────
    function runPipeline() {
      const sel = selectedWeeks()
      if (!sel.length) { alert('Select at least one week'); return }
      const uncovered = sel.filter(w => {
        const entry = weekCoverage.find(c => c.week === w)
        return entry && !entry.covered
      })
      if (uncovered.length > 0) {
        if (!confirm(\`\${uncovered.length} selected week(s) are not covered by GTFS data. Run anyway?\`)) return
      }
      fetch('/api/run', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ weeks: sel })
      }).then(r => r.json()).then(d => { if (!d.ok) alert(d.error) })
    }

    function downloadGtfs() {
      fetch('/api/download-gtfs', { method: 'POST' })
        .then(r => r.json()).then(d => { if (!d.ok) alert(d.error) })
    }

    function downloadAndRefresh() {
      // After download completes, reload GTFS status and recheck coverage
      pendingRecheck = true
      downloadGtfs()
    }

    function stopProcess() {
      fetch('/api/stop', { method: 'POST' }).then(r => r.json()).then(d => { if (!d.ok) alert(d.error) })
    }

    function clearConsole() { document.getElementById('console').textContent = '' }

    // ── UI state ─────────────────────────────────────────────────────────────
    let pendingRecheck = false

    function setRunning(r) {
      running = r
      document.getElementById('run-btn').disabled = r
      document.getElementById('stop-btn').disabled = !r
      document.getElementById('dl-btn').disabled = r
      const dot = document.getElementById('dot')
      dot.className = 'dot ' + (r ? 'running' : 'idle')
      document.getElementById('status-text').textContent = r ? 'Running…' : 'Idle'
    }

    function appendLog(text) {
      const el = document.getElementById('console')
      el.textContent += text
      el.scrollTop = el.scrollHeight
    }

    // ── SSE ──────────────────────────────────────────────────────────────────
    const evtSource = new EventSource('/api/events')
    evtSource.onmessage = e => {
      const { type, data } = JSON.parse(e.data)
      if (type === 'start') { setRunning(true); clearConsole() }
      if (type === 'log')   { appendLog(data) }
      if (type === 'done')  {
        setRunning(false)
        const dot = document.getElementById('dot')
        dot.className = 'dot ' + (data.success ? 'done' : 'error')
        document.getElementById('status-text').textContent = data.success ? 'Done ✓' : 'Failed ✗'
        if (pendingRecheck) {
          pendingRecheck = false
          loadGtfsStatus()
          if (weekCoverage.length) computeWeeks()
        }
      }
    }

    // ── Init ─────────────────────────────────────────────────────────────────
    loadGtfsStatus()
    computeWeeks()
  </script>
</body>
</html>`

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`)

  // ── SSE
  if (url.pathname === '/api/events') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' })
    res.write('\n')
    sseClients.push(res)
    req.on('close', () => { sseClients = sseClients.filter(c => c !== res) })
    return
  }

  // ── GTFS coverage info
  if (url.pathname === '/api/gtfs-coverage' && req.method === 'GET') {
    const cov = getGtfsCoverage()
    res.writeHead(200, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify({
      available: cov.available,
      feedStart: cov.feedStart,
      feedEnd: cov.feedEnd,
      feedVersion: cov.feedVersion,
    }))
  }

  // ── Weeks + coverage check
  if (url.pathname === '/api/weeks-coverage' && req.method === 'GET') {
    const start = url.searchParams.get('start')
    const end   = url.searchParams.get('end')
    if (!start || !end) { res.writeHead(400); return res.end('{}') }
    const weeks = weeksBetween(start, end)
    const coverage = checkWeeksCoverage(weeks)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify({ weeks: coverage }))
  }

  // ── Legacy weeks endpoint
  if (url.pathname === '/api/weeks' && req.method === 'GET') {
    const start = url.searchParams.get('start')
    const end   = url.searchParams.get('end')
    res.writeHead(200, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify({ weeks: weeksBetween(start, end) }))
  }

  // ── Run pipeline
  if (url.pathname === '/api/run' && req.method === 'POST') {
    let body = ''
    req.on('data', d => body += d)
    req.on('end', () => {
      const { weeks } = JSON.parse(body)
      res.writeHead(200, { 'Content-Type': 'application/json' })
      res.end(JSON.stringify(runPipeline(weeks)))
    })
    return
  }

  // ── Download GTFS
  if (url.pathname === '/api/download-gtfs' && req.method === 'POST') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(downloadGtfs()))
    return
  }

  // ── Stop
  if (url.pathname === '/api/stop' && req.method === 'POST') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    return res.end(JSON.stringify(stopProcess()))
  }

  // ── HTML
  if (url.pathname === '/' || url.pathname === '/index.html') {
    res.writeHead(200, { 'Content-Type': 'text/html' })
    return res.end(HTML)
  }

  res.writeHead(404)
  res.end('Not found')
})

server.listen(PORT, () => {
  console.log(`Pipeline admin UI running at http://localhost:${PORT}`)
})
