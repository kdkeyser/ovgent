import maplibregl from 'maplibre-gl'
import type { FilterMode } from './types'
import { colorExpression } from './colors'

export function initMap(): maplibregl.Map {
  return new maplibregl.Map({
    container: 'map',
    style: 'https://tiles.stadiamaps.com/styles/alidade_smooth_dark.json',
    center: [3.7174, 51.0543],
    zoom: 12
  })
}

export function addHexLayer(map: maplibregl.Map, mode: FilterMode): void {
  map.addSource('hexes', {
    type: 'geojson',
    data: '/ghent-hexes.geojson'
  })
  map.addLayer({
    id: 'hex-fill',
    type: 'fill',
    source: 'hexes',
    paint: {
      'fill-color': colorExpression(mode),
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

export function addStopLayer(map: maplibregl.Map, mode: FilterMode): void {
  map.addSource('stops', {
    type: 'geojson',
    data: '/ghent-stops.geojson'
  })
  map.addLayer({
    id: 'stops-circle',
    type: 'circle',
    source: 'stops',
    filter: ['==', ['get', `qualifies_${mode}`], true],
    paint: {
      'circle-color': '#60a5fa',
      'circle-radius': 4,
      'circle-stroke-color': '#fff',
      'circle-stroke-width': 1
    }
  })
}

export function updateMode(map: maplibregl.Map, mode: FilterMode): void {
  map.setPaintProperty('hex-fill', 'fill-color', colorExpression(mode))
  map.setFilter('stops-circle', ['==', ['get', `qualifies_${mode}`], true])
}
