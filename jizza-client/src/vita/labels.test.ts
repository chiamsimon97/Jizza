import { describe, expect, it } from 'vitest'
import { describeFigure } from './labels'

describe('describeFigure', () => {
  it('words the seeded figures from their own values only', () => {
    expect(describeFigure('clinical.ranges.general.bp_home', { kind: 'bp', unit: 'mmHg', systolic: { below: 135 }, diastolic: { below: 85 } })).toBe('Below 135/85 mmHg')
    expect(describeFigure('clinical.ranges.diabetes_t2.glucose_before_meal', { unit: 'mg/dL', low: 80, high: 130 })).toBe('80–130 mg/dL')
    expect(describeFigure('clinical.ranges.diabetes_t2.glucose_after_meal', { unit: 'mg/dL', below: 180 })).toBe('Below 180 mg/dL')
    expect(describeFigure('clinical.ranges.diabetes_t2.hba1c', { unit: '%', atMost: 7.0 })).toBe('7.0% or lower')
    expect(describeFigure('clinical.redflags.bp', { unit: 'mmHg', systolicAbove: 180, diastolicAbove: 120 })).toBe('Systolic above 180 or diastolic above 120 mmHg')
    expect(describeFigure('clinical.intervals.diabetes_t2.eye', { timesPerYearAtLeast: 1 })).toBe('At least once a year')
    expect(describeFigure('clinical.intervals.diabetes_t2.hba1c', { atTarget: { timesPerYearAtLeast: 2 }, notAtTargetOrAfterChange: { aboutEveryMonths: 3 } }))
      .toBe('At least twice a year at target; about every 3 months when not, or after a change of treatment')
    expect(describeFigure('clinical.ranges.hypertension.bp_home', { sameAs: 'clinical.ranges.general.bp_home' })).toMatch(/Same as/)
  })

  it('has no number for a figure that is not set', () => {
    expect(describeFigure('clinical.redflags.high_sugar', null)).toBe('Not set: ask your doctor')
  })
})
