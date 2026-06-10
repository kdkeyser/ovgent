export type FilterMode = 'min1' | 'min10' | 'hourly' | 'halfhour'

export interface HexProperties {
  h3index: string
  walking_minutes_min1: number | null
  walking_minutes_min10: number | null
  walking_minutes_hourly: number | null
  walking_minutes_halfhour: number | null
  nearest_stop_min1: string | null
  nearest_stop_min10: string | null
  nearest_stop_hourly: string | null
  nearest_stop_halfhour: string | null
}

export interface StopProperties {
  stop_id: string
  stop_name: string
  qualifies_min1: boolean
  qualifies_min10: boolean
  qualifies_hourly: boolean
  qualifies_halfhour: boolean
  departures_MONDAY: number
  departures_TUESDAY: number
  departures_WEDNESDAY: number
  departures_THURSDAY: number
  departures_FRIDAY: number
  departures_SATURDAY: number
  departures_SUNDAY: number
}
