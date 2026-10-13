import { JizzaOrb } from '@/jizza/JizzaOrb'

export type AgentState = 'idle' | 'working' | 'off'

interface AgentLogoProps {
  agentKey: string
  state?: AgentState
  size?: number
  className?: string
}

/** One line on what each agent is busy with, matching what its logo acts out. */
export const AGENT_ACTIVITY: Record<string, { idle: string; working: string }> = {
  jizza:        { idle: 'Listening for you',            working: 'Coordinating the team' },
  health:       { idle: 'Keeping an eye on your health', working: 'Checking your vitals' },
  finance:      { idle: 'Watching your money',           working: 'Crunching the numbers' },
  professional: { idle: 'Minding your workday',          working: 'Drafting and scheduling' },
}

/**
 * A living logo for each agent that acts out its job: Vita's heart beats and traces a pulse, Kai's
 * bars climb while a coin flips, Cato's letter slides out of its envelope. Idle is a gentle loop,
 * working is the full motion, off is asleep.
 */
export function AgentLogo({ agentKey, state = 'idle', size = 40, className = '' }: AgentLogoProps) {
  if (agentKey === 'jizza') {
    return <JizzaOrb size={size} state={state === 'working' ? 'thinking' : 'idle'} tracking={state !== 'off'}
      className={`${state === 'off' ? 'grayscale opacity-50' : ''} ${className}`} />
  }
  const Art = ART[agentKey] ?? Generic
  return (
    <span className={`relative inline-block shrink-0 ${className}`} style={{ width: size, height: size }}>
      <svg viewBox="0 0 64 64" width={size} height={size} data-state={state} aria-hidden
        className={`agent-logo transition-[filter,opacity] duration-300 ${state === 'off' ? 'grayscale opacity-50' : ''}`}>
        <Art />
      </svg>
      {state === 'off' && (
        <span className="agent-zzz absolute -right-1 -top-1 text-[10px] font-bold text-gray-400" style={{ fontSize: Math.max(9, size * 0.22) }}>z</span>
      )}
      {state === 'working' && (
        <span className="absolute -bottom-0.5 -right-0.5 h-2.5 w-2.5 rounded-full border-2 border-white bg-green-500 dark:border-gray-900">
          <span className="absolute inset-0 animate-ping rounded-full bg-green-500" />
        </span>
      )}
    </span>
  )
}

function Tile({ from, to }: { from: string; to: string }) {
  const id = `t-${from.replace(/\W/g, '')}-${to.replace(/\W/g, '')}`
  return (
    <>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={from} />
          <stop offset="1" stopColor={to} />
        </linearGradient>
      </defs>
      <rect className="al-tile" width="64" height="64" rx="16" fill={`url(#${id})`} />
    </>
  )
}

/** Vita: a heart that beats, with a pulse line running across it. */
function Vita() {
  return (
    <>
      <Tile from="#fb7185" to="#e11d48" />
      <g className="al-heart">
        <path d="M32 48 C14 36 12 24 20 19 c5 -3 10 -1 12 4 c2 -5 7 -7 12 -4 c8 5 6 17 -12 29z" fill="white" opacity="0.95" />
      </g>
      <polyline className="al-ecg" points="10,34 22,34 26,26 31,42 35,30 38,34 54,34" fill="none" stroke="#e11d48"
        strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
    </>
  )
}

/** Kai: bars that climb and a coin that flips. */
function Kai() {
  return (
    <>
      <Tile from="#34d399" to="#047857" />
      <g fill="white">
        <rect className="al-bar al-bar-1" x="13" y="34" width="8" height="16" rx="2" />
        <rect className="al-bar al-bar-2" x="25" y="27" width="8" height="23" rx="2" />
        <rect className="al-bar al-bar-3" x="37" y="20" width="8" height="30" rx="2" />
      </g>
      <g className="al-coin">
        <circle cx="47" cy="17" r="8" fill="#fbbf24" stroke="white" strokeWidth="2" />
        <text x="47" y="21" textAnchor="middle" fontSize="11" fontWeight="700" fill="#92400e">$</text>
      </g>
    </>
  )
}

/** Cato: an envelope whose letter slides out while a pen line writes itself. */
function Cato() {
  return (
    <>
      <Tile from="#60a5fa" to="#4338ca" />
      <g className="al-letter">
        <rect x="19" y="14" width="26" height="26" rx="3" fill="white" />
        <line className="al-write" x1="23" y1="21" x2="41" y2="21" stroke="#4338ca" strokeWidth="2.5" strokeLinecap="round" />
        <line className="al-write al-write-2" x1="23" y1="27" x2="36" y2="27" stroke="#93c5fd" strokeWidth="2.5" strokeLinecap="round" />
      </g>
      <path d="M12 30 L32 42 L52 30 V50 a3 3 0 0 1 -3 3 H15 a3 3 0 0 1 -3 -3z" fill="white" opacity="0.95" />
      <path d="M12 30 L32 42 L52 30" fill="none" stroke="#c7d2fe" strokeWidth="2" strokeLinejoin="round" />
    </>
  )
}

/** Any other agent: a gear that turns while it works. */
function Generic() {
  return (
    <>
      <Tile from="#a78bfa" to="#6d28d9" />
      <g className="al-gear">
        <path fill="white" d="M32 18l3 1 1 5 4 2 4-3 3 3-3 4 2 4 5 1v6l-5 1-2 4 3 4-3 3-4-3-4 2-1 5h-6l-1-5-4-2-4 3-3-3 3-4-2-4-5-1v-6l5-1 2-4-3-4 3-3 4 3 4-2 1-5z" />
        <circle cx="32" cy="35" r="6" fill="#6d28d9" />
      </g>
    </>
  )
}

const ART: Record<string, () => JSX.Element> = {
  health: Vita,
  finance: Kai,
  professional: Cato,
}
