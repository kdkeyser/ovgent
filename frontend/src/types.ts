export type FilterMode = 'min1' | 'min10' | 'hourly' | 'halfhour'
export type DayScope = 'weekday' | 'fullweek'

export interface HexProperties {
  walking_minutes_min1_weekday: number | null
  walking_minutes_min1_fullweek: number | null
  walking_minutes_min10_weekday: number | null
  walking_minutes_min10_fullweek: number | null
  walking_minutes_hourly_weekday: number | null
  walking_minutes_hourly_fullweek: number | null
  walking_minutes_halfhour_weekday: number | null
  walking_minutes_halfhour_fullweek: number | null
  nearest_stop_min1_weekday: string | null
  nearest_stop_min1_fullweek: string | null
  nearest_stop_min10_weekday: string | null
  nearest_stop_min10_fullweek: string | null
  nearest_stop_hourly_weekday: string | null
  nearest_stop_hourly_fullweek: string | null
  nearest_stop_halfhour_weekday: string | null
  nearest_stop_halfhour_fullweek: string | null
}

export interface StopProperties {
  stop_id: string
  stop_name: string
  qualifies_min1_weekday: boolean
  qualifies_min1_fullweek: boolean
  qualifies_min10_weekday: boolean
  qualifies_min10_fullweek: boolean
  qualifies_hourly_weekday: boolean
  qualifies_hourly_fullweek: boolean
  qualifies_halfhour_weekday: boolean
  qualifies_halfhour_fullweek: boolean
  departures_MONDAY: number
  departures_TUESDAY: number
  departures_WEDNESDAY: number
  departures_THURSDAY: number
  departures_FRIDAY: number
  departures_SATURDAY: number
  departures_SUNDAY: number
}

export interface WeekEntry {
  id: string
  label: string
  startDate: string
  coverageWarning: string | null
}

export interface WeeksManifest {
  weeks: WeekEntry[]
}
