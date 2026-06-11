import type maplibregl from 'maplibre-gl'
import type { FilterMode, DayScope } from './types'

export const LEGEND_ENTRIES = [
  { label: '0–5 min', color: '#22c55e' },
  { label: '5–10 min', color: '#86efac' },
  { label: '10–15 min', color: '#fbbf24' },
  { label: '15–20 min', color: '#f97316' },
  { label: '20+ min', color: '#ef4444' },
  { label: 'No stop', color: '#475569' },
]

export function colorExpression(mode: FilterMode, scope: DayScope): maplibregl.ExpressionSpecification {
  const prop = `walking_minutes_${mode}_${scope}`
  return [
    'case',
    ['==', ['coalesce', ['get', prop], -1], -1], '#475569',
    ['<=', ['get', prop], 5],    '#22c55e',
    ['<=', ['get', prop], 10],   '#86efac',
    ['<=', ['get', prop], 15],   '#fbbf24',
    ['<=', ['get', prop], 20],   '#f97316',
    '#ef4444'
  ]
}
