import type { EmergencyCard } from '@/types/vita'
import { formatDay } from '@/portfolio/components/AsOf'
import { BLOOD_GROUPS, SEVERITIES } from '../labels'

function organ(v: boolean | null) {
  return v === true ? 'Yes' : v === false ? 'No' : 'Not set'
}

/** The card's lines, in the order a hospital reads them; shared by the screen and the lock-screen image. */
export function cardLines(c: EmergencyCard): [string, string][] {
  const allergies = c.allergies.length
    ? c.allergies.map(a => `${a.substance}${a.severity && a.severity !== 'unknown' ? ` (${SEVERITIES[a.severity as keyof typeof SEVERITIES]?.toLowerCase() ?? a.severity})` : ''}${a.reaction ? `: ${a.reaction}` : ''}`).join(', ')
    : c.allergiesNoneKnown ? 'None known' : 'Not recorded'
  const conditions = c.conditions.length ? c.conditions.map(x => x.name).join(', ') : c.conditionsNoneKnown ? 'None known' : 'Not recorded'
  const medicines = c.medicinesAvailable ? (c.medicines.map(m => m.name).join(', ') || 'None') : 'Added with the next update'
  return [
    ['Allergies', allergies],
    ['Conditions', conditions],
    ['Medicines', medicines],
    ['Contacts', c.emergencyContacts.map(k => `${k.name}${k.relation ? ` (${k.relation.toLowerCase()})` : ''} ${k.phone}`).join('\n') || 'None added'],
    ['Hospital', c.preferredHospital ? `${c.preferredHospital}${c.preferredHospitalPhone ? ` · ${c.preferredHospitalPhone}` : ''}` : 'Not set'],
    ['Organ donor', organ(c.organDonor)],
    ['Emergency', [c.emergencyNumbers?.emergency, c.emergencyNumbers?.ambulanceWhereTheyLive && `Ambulance ${c.emergencyNumbers.ambulanceWhereTheyLive}`].filter(Boolean).join(' · ') || 'Not set'],
  ]
}

/**
 * The emergency card (7.4): card-shaped, readable at a glance, and the only thing printed when the
 * person presses Print (one page). Severe allergies come first, as the server sorts them.
 */
export function EmergencyCardView({ card }: { card: EmergencyCard }) {
  const blood = card.bloodGroup ? BLOOD_GROUPS[card.bloodGroup] : 'Not set'
  const severe = card.allergies.some(a => a.severity === 'severe')
  return (
    <article id="vita-emergency-card" aria-label={`Emergency card for ${card.name}`}
      className="mx-auto flex w-full max-w-md flex-col gap-3 rounded-2xl border-2 border-[rgb(var(--agent-health))] bg-white p-5 text-gray-900 shadow-sm dark:bg-gray-900 dark:text-gray-100 print:max-w-none print:border-black print:shadow-none">
      <header className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-gray-500 dark:text-gray-400">Emergency card</p>
          <p className="text-xl font-bold">{card.name}</p>
          <p className="text-sm text-gray-600 dark:text-gray-300">{card.age != null ? `${card.age} years` : 'Age not set'}</p>
        </div>
        <div className="text-center">
          <p className="text-xs text-gray-500 dark:text-gray-400">Blood group</p>
          <p className="font-mono text-2xl font-bold text-[rgb(var(--agent-health))]">{blood}</p>
        </div>
      </header>
      <dl className="grid grid-cols-[7rem_1fr] gap-x-3 gap-y-2 text-sm">
        {cardLines(card).map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-gray-500 dark:text-gray-400">{k}</dt>
            <dd className={`whitespace-pre-line ${k === 'Allergies' && severe ? 'font-semibold text-red-700 dark:text-red-300' : ''} ${k === 'Emergency' ? 'font-mono' : ''} tabular-nums`}>
              {k === 'Allergies' && severe && <span className="sr-only">Includes a severe allergy: </span>}{v}
            </dd>
          </div>
        ))}
      </dl>
      <p className="text-xs text-gray-500 dark:text-gray-400">Updated on {formatDay(card.updatedOn)}{card.closed ? ' · record closed' : ''}</p>
    </article>
  )
}

/** Draws the card as a tall image for a phone's lock screen; returns a PNG data URL, or null without a canvas. */
export function cardImage(card: EmergencyCard): string | null {
  const canvas = document.createElement('canvas')
  canvas.width = 1080
  canvas.height = 1920
  const g = canvas.getContext('2d')
  if (!g) return null
  g.fillStyle = '#ffffff'
  g.fillRect(0, 0, 1080, 1920)
  g.fillStyle = '#e11d48'
  g.fillRect(0, 560, 1080, 12)
  g.fillStyle = '#111827'
  g.font = 'bold 44px Inter, sans-serif'
  g.fillText('EMERGENCY CARD', 80, 660)
  g.font = 'bold 72px Inter, sans-serif'
  g.fillText(card.name, 80, 760)
  g.font = '44px Inter, sans-serif'
  g.fillText(`${card.age != null ? `${card.age} years · ` : ''}Blood group ${card.bloodGroup ? BLOOD_GROUPS[card.bloodGroup] : 'not set'}`, 80, 830)
  let y = 930
  for (const [k, v] of cardLines(card)) {
    g.font = 'bold 36px Inter, sans-serif'
    g.fillText(k, 80, y)
    g.font = '36px Inter, sans-serif'
    for (const line of wrap(g, v, 900)) {
      y += 46
      g.fillText(line, 80, y)
    }
    y += 64
    if (y > 1860) break
  }
  return canvas.toDataURL('image/png')
}

function wrap(g: CanvasRenderingContext2D, text: string, width: number): string[] {
  const out: string[] = []
  for (const para of text.split('\n')) {
    let line = ''
    for (const word of para.split(' ')) {
      const next = line ? `${line} ${word}` : word
      if (g.measureText(next).width > width && line) { out.push(line); line = word } else line = next
    }
    out.push(line)
  }
  return out
}
