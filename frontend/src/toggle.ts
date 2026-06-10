import type { FilterMode } from './types'
import { updateMode } from './map'
import type maplibregl from 'maplibre-gl'

export function initToggle(map: maplibregl.Map, onModeChange: (mode: FilterMode) => void): void {
  const select = document.getElementById('mode-select') as HTMLSelectElement

  select.addEventListener('change', () => {
    const mode = select.value as FilterMode
    updateMode(map, mode)
    onModeChange(mode)
  })
}
