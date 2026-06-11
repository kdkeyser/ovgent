import 'maplibre-gl/dist/maplibre-gl.css'
import type { FilterMode, DayScope } from './types'
import { initMap, addHexLayer, addStopLayer, updateModeAndScope, setStopsVisible } from './map'
import { initToggle, initScopeToggle, initWeekControls, initStopsToggle } from './toggle'
import { initTooltip } from './tooltip'
import { renderLegend } from './legend'
import { loadManifest, initWeekSources, swapWeekSources, sortedWeeks } from './weeks'

let currentMode: FilterMode = 'min1'
let currentDayScope: DayScope = 'weekday'
let currentWeekId = ''

const map = initMap()

map.on('load', async () => {
  let manifest
  try {
    manifest = await loadManifest()
  } catch {
    document.getElementById('controls')!.innerHTML =
      '<p style="color:#f87171;padding:8px">No data — run the pipeline with <code>--week YYYY-Www</code> first.</p>'
    return
  }

  const weeks = sortedWeeks(manifest)
  if (weeks.length === 0) {
    document.getElementById('controls')!.innerHTML =
      '<p style="color:#f87171;padding:8px">Manifest is empty — run the pipeline first.</p>'
    return
  }

  currentWeekId = weeks[0].id

  initWeekSources(map, currentWeekId)
  addHexLayer(map, currentMode, currentDayScope)
  addStopLayer(map, currentMode, currentDayScope)

  initToggle((mode) => {
    currentMode = mode
    updateModeAndScope(map, currentMode, currentDayScope)
  })

  initScopeToggle((scope) => {
    currentDayScope = scope
    updateModeAndScope(map, currentMode, currentDayScope)
  })

  initWeekControls(weeks, currentWeekId, (weekId) => {
    currentWeekId = weekId
    swapWeekSources(map, currentWeekId)
  })

  initStopsToggle((visible) => setStopsVisible(map, visible))
  initTooltip(map, () => currentMode, () => currentDayScope)
  renderLegend()
})
