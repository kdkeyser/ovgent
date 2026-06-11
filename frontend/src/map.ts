import maplibregl from 'maplibre-gl'
import type { FilterMode, DayScope } from './types'
import { colorExpression } from './colors'

export function initMap(): maplibregl.Map {
  return new maplibregl.Map({
    container: 'map',
    style: 'https://tiles.openfreemap.org/styles/dark',
    center: [3.7174, 51.0543],
    zoom: 12
  })
}

export function addHexLayer(map: maplibregl.Map, mode: FilterMode, scope: DayScope): void {
  map.addLayer({
    id: 'hex-fill',
    type: 'fill',
    source: 'hexes',
    paint: {
      'fill-color': colorExpression(mode, scope),
      'fill-opacity': 0.35
    }
  })
  map.addLayer({
    id: 'hex-outline',
    type: 'line',
    source: 'hexes',
    paint: {
      'line-color': '#0f172a',
      'line-width': 0.4
    }
  })
}

export function addStopLayer(map: maplibregl.Map, mode: FilterMode, scope: DayScope): void {
  map.addLayer({
    id: 'stops-circle',
    type: 'circle',
    source: 'stops',
    filter: ['==', ['get', `qualifies_${mode}_${scope}`], true],
    paint: {
      'circle-color': '#60a5fa',
      'circle-radius': 4,
      'circle-stroke-color': '#fff',
      'circle-stroke-width': 1
    }
  })
}

export function setStopsVisible(map: maplibregl.Map, visible: boolean): void {
  map.setPaintProperty('stops-circle', 'circle-opacity', visible ? 1 : 0)
  map.setPaintProperty('stops-circle', 'circle-stroke-opacity', visible ? 1 : 0)
}

export function updateModeAndScope(map: maplibregl.Map, mode: FilterMode, scope: DayScope): void {
  map.setPaintProperty('hex-fill', 'fill-color', colorExpression(mode, scope))
  map.setFilter('stops-circle', ['==', ['get', `qualifies_${mode}_${scope}`], true])
}
