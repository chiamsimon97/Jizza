import { useQuery } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import type { VitaConfigItem } from '@/types/vita'
import { vitaKeys } from './queryKeys'

export function errorText(e: unknown, fallback: string) {
  return e instanceof Error && e.message ? e.message : fallback
}

export function useVitaPeople() {
  return useQuery({ queryKey: vitaKeys.people, queryFn: vitaApi.people })
}

export function useVitaConfig() {
  return useQuery({ queryKey: vitaKeys.config, queryFn: vitaApi.config, staleTime: 60_000 })
}

export interface Units { weight: 'kg' | 'lb'; height: 'cm' | 'ft_in'; temperature: 'C' | 'F' }
export const DEFAULT_UNITS: Units = { weight: 'kg', height: 'cm', temperature: 'C' }

/** The units the person chose in Settings; values are always stored in metric units. */
export function unitsOf(items: VitaConfigItem[] | undefined): Units {
  const v = items?.find(i => i.key === 'display.units')?.value
  if (!v || typeof v !== 'object' || Array.isArray(v)) return DEFAULT_UNITS
  return {
    weight: v.weight === 'lb' ? 'lb' : 'kg',
    height: v.height === 'ft_in' ? 'ft_in' : 'cm',
    temperature: v.temperature === 'F' ? 'F' : 'C',
  }
}

export function useUnits(): Units {
  return unitsOf(useVitaConfig().data)
}

/** A pound in kilograms, by definition (a unit conversion, not a clinical figure). */
const KG_PER_LB = 0.45359237
const CM_PER_IN = 2.54

export function showWeight(kg: number, unit: Units['weight']): string {
  return unit === 'lb' ? `${(kg / KG_PER_LB).toFixed(1)} lb` : `${Number(kg.toFixed(2))} kg`
}

export function showHeight(cm: number | null | undefined, unit: Units['height']): string {
  if (cm == null) return 'Not set'
  if (unit === 'cm') return `${Number(cm)} cm`
  const inches = Math.round(cm / CM_PER_IN)
  return `${Math.floor(inches / 12)} ft ${inches % 12} in`
}

/** Feet and inches typed by the person, as centimetres to one decimal. */
export function feetToCm(ft: number, inches: number): number {
  return Math.round((ft * 12 + inches) * CM_PER_IN * 10) / 10
}

/** "2026-11-27T02:30:00Z" → "27 Nov 2026, 8:00 AM" in India time, where the household lives. */
export function formatWhen(iso: string | null | undefined, timeZone = 'Asia/Kolkata'): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString('en-IN', { timeZone, day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' })
}

/** An instant as a datetime-local value ("2026-11-27T08:30") in the household's time zone. */
export function toLocalInput(iso: string | null | undefined, timeZone = 'Asia/Kolkata'): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-GB', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(d).map(p => [p.type, p.value]))
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
}
