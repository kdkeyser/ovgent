import type maplibregl from 'maplibre-gl'
import type { HexProperties, StopProperties, FilterMode } from './types'

const DAY_LABELS: Record<string, string> = {
  MONDAY: 'Mon', TUESDAY: 'Tue', WEDNESDAY: 'Wed', THURSDAY: 'Thu',
  FRIDAY: 'Fri', SATURDAY: 'Sat', SUNDAY: 'Sun'
}

const DAY_ORDER = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']

export function initTooltip(map: maplibregl.Map, getMode: () => FilterMode): void {
  const el = document.getElementById('tooltip') as HTMLElement

  function show(e: maplibregl.MapMouseEvent, html: string) {
    el.style.display = 'block'
    el.style.left = `${e.point.x + 12}px`
    el.style.top = `${e.point.y + 12}px`
    el.innerHTML = html
  }

  function hide() {
    el.style.display = 'none'
  }

  map.on('mousemove', 'hex-fill', (e) => {
    if (!e.features?.length) return
    const props = e.features[0].properties as HexProperties
    const mode = getMode()
    const minutes = props[`walking_minutes_${mode}` as keyof HexProperties] as number | null
    const stopName = props[`nearest_stop_${mode}` as keyof HexProperties] as string | null

    const hasData = minutes !== null && minutes !== ('null' as unknown)
    show(e, hasData
      ? `<strong>${Math.round(minutes as number)} min walk</strong><br>to ${stopName}`
      : `<em>No qualifying stop nearby</em>`)
  })

  map.on('mouseleave', 'hex-fill', hide)
  map.on('mouseenter', 'hex-fill', () => { map.getCanvas().style.cursor = 'crosshair' })
  map.on('mouseleave', 'hex-fill', () => { map.getCanvas().style.cursor = '' })

  map.on('mousemove', 'stops-circle', (e) => {
    if (!e.features?.length) return
    const props = e.features[0].properties as StopProperties
    const rows = DAY_ORDER
      .map(d => `${DAY_LABELS[d]}: ${props[`departures_${d}` as keyof StopProperties] ?? 0}`)
      .join('<br>')
    show(e, `<strong>${props.stop_name}</strong><br>${rows}`)
  })

  map.on('mouseleave', 'stops-circle', hide)
  map.on('mouseenter', 'stops-circle', () => { map.getCanvas().style.cursor = 'pointer' })
  map.on('mouseleave', 'stops-circle', () => { map.getCanvas().style.cursor = '' })
}
