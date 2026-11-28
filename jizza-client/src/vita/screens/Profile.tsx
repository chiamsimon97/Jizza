import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { vitaApi } from '@/api/vita'
import { Button } from '@/components/ui/Button'
import { SelectField, TextField } from '@/portfolio/components/Fields'
import { LoadFailed, Panel } from '@/portfolio/components/Panel'
import { SkeletonRows } from '@/portfolio/components/SkeletonRows'
import { formatDay } from '@/portfolio/components/AsOf'
import type { BloodGroup, ReminderMode, SexAtBirth, VitaProfileInput, VitaProfileView } from '@/types/vita'
import { Chip, ViewTitle } from '../components/Common'
import { errorText, feetToCm, useUnits } from '../data'
import { BLOOD_GROUPS, REMINDER_MODES, SEXES } from '../labels'
import { vitaKeys } from '../queryKeys'

const ZONES = ['Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Europe/London', 'America/New_York', 'America/Los_Angeles', 'Australia/Sydney']

/** Profile (7.4), opened from the emergency card and Settings. */
export function Profile({ personId, readOnly, onOpen }: { personId: string; readOnly: boolean; onOpen: (view: string) => void }) {
  const { data, isLoading, error, refetch } = useQuery({ queryKey: vitaKeys.profile(personId), queryFn: () => vitaApi.profile(personId) })
  return (
    <div className="flex flex-col gap-4">
      <ViewTitle title={data ? `Profile for ${data.person.displayName}` : 'Profile'} sub="What the emergency card and every reading builds on">
        <Button size="sm" variant="secondary" onClick={() => onOpen('emergency')}>Emergency card</Button>
        <Button size="sm" variant="secondary" onClick={() => onOpen('settings')}>Settings</Button>
      </ViewTitle>
      {isLoading && <SkeletonRows rows={6} />}
      {error && <LoadFailed text="Could not load the profile." onRetry={() => refetch()} />}
      {data && <ProfileForm key={data.profile.updatedAt ?? data.profile.id} personId={personId} view={data} readOnly={readOnly} />}
    </div>
  )
}

type Tri = 'yes' | 'no' | ''
const tri = (v: boolean | null): Tri => (v === true ? 'yes' : v === false ? 'no' : '')

function ProfileForm({ personId, view, readOnly }: { personId: string; view: VitaProfileView; readOnly: boolean }) {
  const qc = useQueryClient()
  const units = useUnits()
  const p = view.profile
  const inches = p.heightCm != null ? Math.round(p.heightCm / 2.54) : null
  const [v, setV] = useState({
    dob: view.person.dob ?? '', sexAtBirth: (p.sexAtBirth ?? '') as SexAtBirth | '', heightCm: p.heightCm != null ? String(p.heightCm) : '',
    feet: inches != null ? String(Math.floor(inches / 12)) : '', inches: inches != null ? String(inches % 12) : '',
    bloodGroup: (p.bloodGroup ?? '') as BloodGroup | '', organDonor: tri(p.organDonor), timezone: p.timezone || 'Asia/Kolkata',
    preferredHospital: p.preferredHospital ?? '', preferredHospitalPhone: p.preferredHospitalPhone ?? '',
    reminderMode: p.reminderMode, abha: '',
  })
  const [saved, setSaved] = useState(false)
  const set = (patch: Partial<typeof v>) => { setSaved(false); setV(x => ({ ...x, ...patch })) }

  const height = (): number | null => {
    if (units.height === 'ft_in') return v.feet === '' ? null : feetToCm(Number(v.feet), Number(v.inches || 0))
    return v.heightCm === '' ? null : Number(v.heightCm)
  }
  const save = useMutation({
    mutationFn: (extra: VitaProfileInput = {}) => {
      const body: VitaProfileInput = {
        dob: v.dob || null, sexAtBirth: v.sexAtBirth || null, heightCm: height(), bloodGroup: v.bloodGroup || null,
        organDonor: v.organDonor === '' ? null : v.organDonor === 'yes', timezone: v.timezone,
        preferredHospital: v.preferredHospital.trim() || null, preferredHospitalPhone: v.preferredHospitalPhone.trim() || null,
        reminderMode: v.reminderMode, ...extra,
      }
      if (v.abha.trim()) body.abhaNumber = v.abha.trim()
      return vitaApi.saveProfile(personId, body)
    },
    onSuccess: () => { setSaved(true); qc.invalidateQueries({ queryKey: vitaKeys.person(personId) }); qc.invalidateQueries({ queryKey: vitaKeys.people }) },
  })
  const opts = <T extends string>(m: Record<T, string>, none = 'Not set') => [{ value: '' as T | '', label: none }, ...Object.entries(m).map(([value, label]) => ({ value: value as T, label: label as string }))]

  return (
    <form className="flex flex-col gap-4" onSubmit={e => { e.preventDefault(); save.mutate({}) }}>
      <fieldset disabled={readOnly} className="contents">
        <Panel title={`About ${view.person.displayName}`}>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField label="Date of birth" type="date" value={v.dob} onChange={dob => set({ dob })} />
            <SelectField label="Sex at birth" value={v.sexAtBirth} onChange={sexAtBirth => set({ sexAtBirth })} options={opts(SEXES)} />
            {units.height === 'cm'
              ? <TextField label="Height (cm)" value={v.heightCm} maxLength={5} placeholder="162" onChange={heightCm => set({ heightCm })} />
              : <div className="grid grid-cols-2 gap-2">
                <TextField label="Height (feet)" value={v.feet} maxLength={1} onChange={feet => set({ feet })} />
                <TextField label="Inches" value={v.inches} maxLength={2} onChange={i => set({ inches: i })} />
              </div>}
            <SelectField label="Blood group" value={v.bloodGroup} onChange={bloodGroup => set({ bloodGroup })} options={opts(BLOOD_GROUPS)} />
            <SelectField label="Organ donor" value={v.organDonor} onChange={organDonor => set({ organDonor })}
              options={[{ value: '', label: 'Not set' }, { value: 'yes', label: 'Yes' }, { value: 'no', label: 'No' }]} />
            <SelectField label="Time zone" value={v.timezone} onChange={timezone => set({ timezone })}
              hint="Reminders follow this time zone." options={[...new Set([v.timezone, ...ZONES])].map(z => ({ value: z, label: z }))} />
            <TextField label="Preferred hospital" value={v.preferredHospital} maxLength={160} onChange={preferredHospital => set({ preferredHospital })} />
            <TextField label="Hospital phone" value={v.preferredHospitalPhone} maxLength={30} placeholder="+91 …" onChange={preferredHospitalPhone => set({ preferredHospitalPhone })} />
            <div className="flex flex-col gap-1 sm:col-span-2">
              <TextField label={p.abhaLast4 ? `ABHA number (ends in ${p.abhaLast4})` : 'ABHA number (optional)'} value={v.abha} maxLength={20}
                placeholder={p.abhaLast4 ? 'Type a new number to replace it' : '14 digits'} onChange={abha => set({ abha })}
                hint="Kept encrypted. Only its last four digits are ever shown or exported." />
              {p.abhaLast4 && !readOnly && (
                <Button type="button" size="sm" variant="ghost" className="self-start" onClick={() => save.mutate({ abhaNumber: null })}>Remove the ABHA number</Button>
              )}
            </div>
          </div>
        </Panel>
        <Panel title="Reminder mode">
          <div className="flex flex-wrap gap-2" role="group" aria-label="Reminder mode">
            {(Object.keys(REMINDER_MODES) as ReminderMode[]).map(m => (
              <Chip key={m} on={v.reminderMode === m} onClick={() => !readOnly && set({ reminderMode: m })}>{REMINDER_MODES[m].label}</Chip>
            ))}
          </div>
          <p className="text-xs text-gray-500 dark:text-gray-400">{REMINDER_MODES[v.reminderMode].about} Reminders start with the medicines update.</p>
        </Panel>
      </fieldset>
      {!readOnly && (
        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" loading={save.isPending}>Save profile</Button>
          {saved && !save.isPending && <span role="status" className="text-sm text-green-700 dark:text-green-400">Saved.</span>}
          {p.updatedAt && <span className="text-xs text-gray-500 dark:text-gray-400">Updated on {formatDay(p.updatedAt)}</span>}
        </div>
      )}
      {save.error && <p role="alert" className="text-sm text-red-600 dark:text-red-400">{errorText(save.error, 'Could not save the profile.')}</p>}
    </form>
  )
}
