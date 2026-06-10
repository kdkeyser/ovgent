import 'maplibre-gl/dist/maplibre-gl.css'
import type { FilterMode } from './types'
import { initMap, addHexLayer, addStopLayer } from './map'
import { initToggle } from './toggle'
import { initTooltip } from './tooltip'
import { renderLegend } from './legend'

let currentMode: FilterMode = 'min1'

const map = initMap()

map.on('load', () => {
  addHexLayer(map, currentMode)
  addStopLayer(map, currentMode)
  initToggle(map, (mode) => { currentMode = mode })
  initTooltip(map, () => currentMode)
  renderLegend()
})
