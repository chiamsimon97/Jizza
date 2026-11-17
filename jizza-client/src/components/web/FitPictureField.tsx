import { useState } from 'react'
import type { PictureFit } from '@/api/web'

const box = 'rounded-md border border-gray-300 bg-transparent px-1.5 py-0.5 text-xs dark:border-gray-600'

/** A size field's text as a whole number in range, or undefined when empty or not one. */
function whole(text: string, max: number): number | undefined {
  const n = Number(text.trim())
  return text.trim() && Number.isInteger(n) && n >= 1 && n <= max ? n : undefined
}

/** The fit the four boxes ask for; null when they ask for nothing. */
export function fitOf(kb: string, width: string, height: string, format: string): PictureFit | null {
  const fit: PictureFit = {
    ...(whole(kb, 10_240) ? { maxKb: whole(kb, 10_240) } : {}),
    ...(whole(width, 10_000) ? { width: whole(width, 10_000) } : {}),
    ...(whole(height, 10_000) ? { height: whole(height, 10_000) } : {}),
    ...(format ? { format: format as PictureFit['format'] } : {}),
  }
  return Object.keys(fit).length > 0 ? fit : null
}

/**
 * What a site asks of a picture ("JPEG under 50 KB, 200 × 230 px"): an Upload step fits the
 * picture to it in the browser before handing it over. It is only ever made smaller.
 */
export function FitPictureField({ fit, busy, onChange }: {
  fit?: PictureFit
  busy?: boolean
  onChange: (next: PictureFit | null) => void
}) {
  const [kb, setKb] = useState(fit?.maxKb?.toString() ?? '')
  const [width, setWidth] = useState(fit?.width?.toString() ?? '')
  const [height, setHeight] = useState(fit?.height?.toString() ?? '')
  const [format, setFormat] = useState<string>(fit?.format ?? '')
  const send = (next = format) => {
    const value = fitOf(kb, width, height, next)
    if (JSON.stringify(value) !== JSON.stringify(fit ?? null)) onChange(value)
  }
  const size = (label: string, value: string, set: (v: string) => void, placeholder: string) => (
    <input aria-label={label} value={value} disabled={busy} inputMode="numeric" placeholder={placeholder}
      onChange={e => set(e.target.value.replace(/[^0-9]/g, ''))} onBlur={() => send()} className={`${box} w-16`} />
  )
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5 text-xs text-gray-600 dark:text-gray-300">
      If it is a picture, fit it to
      {size('Largest size in KB', kb, setKb, 'KB')}
      {size('Width in pixels', width, setWidth, 'width')}×{size('Height in pixels', height, setHeight, 'height')}
      <select aria-label="Picture format" value={format} disabled={busy} className={box}
        onChange={e => { setFormat(e.target.value); send(e.target.value) }}>
        <option value="">Same format</option>
        <option value="jpeg">JPEG</option>
        <option value="png">PNG</option>
        <option value="webp">WebP</option>
      </select>
    </span>
  )
}
