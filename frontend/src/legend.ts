import { LEGEND_ENTRIES } from './colors'

export function renderLegend(): void {
  const el = document.getElementById('legend') as HTMLElement
  el.innerHTML = LEGEND_ENTRIES.map(({ label, color }) =>
    `<div style="display:flex;align-items:center;gap:8px;margin-top:4px">
      <div style="width:14px;height:14px;border-radius:3px;background:${color};flex-shrink:0"></div>
      <span style="color:#cbd5e1;font-size:12px">${label}</span>
    </div>`
  ).join('')
}
