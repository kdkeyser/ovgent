import type { FilterMode, DayScope, WeekEntry } from './types'

export function initToggle(onModeChange: (mode: FilterMode) => void): void {
  const select = document.getElementById('mode-select') as HTMLSelectElement
  select.addEventListener('change', () => {
    onModeChange(select.value as FilterMode)
  })
}

export function initScopeToggle(onScopeChange: (scope: DayScope) => void): void {
  const buttons = document.querySelectorAll<HTMLButtonElement>('.scope-btn')
  buttons.forEach(btn => {
    btn.addEventListener('click', () => {
      buttons.forEach(b => b.classList.remove('active'))
      btn.classList.add('active')
      onScopeChange(btn.dataset.scope as DayScope)
    })
  })
}

export function initWeekControls(
  weeks: WeekEntry[],
  initialWeekId: string,
  onWeekChange: (weekId: string) => void
): void {
  const select = document.getElementById('week-select') as HTMLSelectElement
  const prevBtn = document.getElementById('week-prev') as HTMLButtonElement
  const nextBtn = document.getElementById('week-next') as HTMLButtonElement

  weeks.forEach(w => {
    const opt = document.createElement('option')
    opt.value = w.id
    opt.textContent = w.coverageWarning ? `${w.label} ⚠` : w.label
    select.appendChild(opt)
  })
  select.value = initialWeekId
  syncNavButtons(select, prevBtn, nextBtn)

  select.addEventListener('change', () => {
    syncNavButtons(select, prevBtn, nextBtn)
    onWeekChange(select.value)
  })

  prevBtn.addEventListener('click', () => {
    if (select.selectedIndex < select.options.length - 1) {
      select.selectedIndex++
      select.dispatchEvent(new Event('change'))
    }
  })

  nextBtn.addEventListener('click', () => {
    if (select.selectedIndex > 0) {
      select.selectedIndex--
      select.dispatchEvent(new Event('change'))
    }
  })
}

export function initStopsToggle(onStopsVisibilityChange: (visible: boolean) => void): void {
  const btn = document.getElementById('stops-toggle') as HTMLButtonElement
  let visible = true
  btn.addEventListener('click', () => {
    visible = !visible
    btn.classList.toggle('active', visible)
    btn.textContent = visible ? 'Stops on' : 'Stops off'
    onStopsVisibilityChange(visible)
  })
}

function syncNavButtons(
  select: HTMLSelectElement,
  prevBtn: HTMLButtonElement,
  nextBtn: HTMLButtonElement
): void {
  prevBtn.disabled = select.selectedIndex >= select.options.length - 1
  nextBtn.disabled = select.selectedIndex <= 0
}
