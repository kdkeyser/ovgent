import type maplibregl from 'maplibre-gl'
import type { WeeksManifest, WeekEntry } from './types'

export async function loadManifest(): Promise<WeeksManifest> {
  const res = await fetch('/weeks-manifest.json')
  if (!res.ok) throw new Error('weeks-manifest.json not found')
  return res.json() as Promise<WeeksManifest>
}

export function sortedWeeks(manifest: WeeksManifest): WeekEntry[] {
  return [...manifest.weeks].sort((a, b) => b.startDate.localeCompare(a.startDate))
}

export function initWeekSources(map: maplibregl.Map, weekId: string): void {
  map.addSource('hexes', {
    type: 'geojson',
    data: `/ghent-hexes-${weekId}.geojson`
  })
  map.addSource('stops', {
    type: 'geojson',
    data: `/ghent-stops-${weekId}.geojson`
  })
}

export function swapWeekSources(map: maplibregl.Map, weekId: string): void {
  ;(map.getSource('hexes') as maplibregl.GeoJSONSource).setData(`/ghent-hexes-${weekId}.geojson`)
  ;(map.getSource('stops') as maplibregl.GeoJSONSource).setData(`/ghent-stops-${weekId}.geojson`)
}
