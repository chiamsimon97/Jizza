import { JizzaOrb } from '@/jizza/JizzaOrb'
import { Cheeks, Eyes, type FaceCtx, Mascot, Mouth } from './Mascot'

export type AgentState = 'idle' | 'working' | 'off'

/** How a custom agent looks: one of the mascot colours, chosen when it was made. */
export interface AgentAvatar {
  style?: string
  colour?: string
}

interface AgentLogoProps {
  agentKey: string
  avatar?: AgentAvatar | null
  state?: AgentState
  size?: number
  className?: string
}

/** One line on what each agent is busy with, matching what its character acts out. */
export const AGENT_ACTIVITY: Record<string, { idle: string; working: string }> = {
  jizza:        { idle: 'Listening for you',            working: 'Coordinating the team' },
  health:       { idle: 'Keeping an eye on your health', working: 'Pumping iron for your vitals' },
  finance:      { idle: 'Guarding your piggy bank',      working: 'Counting every coin' },
  professional: { idle: 'Minding your workday',          working: 'Firing off emails' },
}

const QUIPS: Record<string, string[]> = {
  health: ['Drink some water!', 'One more rep!', 'Have you stretched today?', 'My heart skipped a beat!', 'Sleep is a feature, not a bug.'],
  finance: ['Oink! Feed me coins.', 'Ka-ching!', 'Every penny counts!', 'Budget? I love a budget.', "Don't break me, I'm saving!"],
  professional: ['Per my last email…', "Let's circle back.", 'Inbox zero, baby!', "I'll pencil that in.", 'This could have been an email.'],
  other: ['Beep boop!', 'Task acquired.', 'Gears turning!'],
}
const SLEEPY = ['Zzz… five more minutes.', "Shh, I'm paused.", 'Wake me with Resume!']

/**
 * A cartoon character for each agent that acts out its job: Vita the heart lifts weights,
 * Kai the piggy bank swallows coins with dollar-sign eyes, Cato the envelope fires off paper planes.
 * They watch the pointer, grin on hover and blurt something out when poked. Off means asleep.
 */
export function AgentLogo({ agentKey, avatar, state = 'idle', size = 40, className = '' }: AgentLogoProps) {
  if (agentKey === 'jizza') {
    return <JizzaOrb size={size} state={state === 'working' ? 'thinking' : 'idle'} asleep={state === 'off'} className={className} />
  }
  const Art = ART[agentKey] ?? Robo
  const off = state === 'off'
  return (
    <Mascot size={size} state={state} asleep={off} quips={off ? SLEEPY : QUIPS[agentKey] ?? QUIPS.other}
      className={`agent-logo ${className}`}>
      {face => <Art face={face} working={state === 'working'} colour={avatar?.colour} />}
    </Mascot>
  )
}

type ArtProps = { face: FaceCtx; working: boolean; colour?: string }

/** Body, screen and trim for each colour a custom agent can have. */
export const AVATAR_COLOURS: Record<string, [string, string, string]> = {
  purple: ['#8b5cf6', '#ede9fe', '#6d28d9'],
  teal: ['#14b8a6', '#ccfbf1', '#0f766e'],
  coral: ['#fb7185', '#ffe4e6', '#be123c'],
  blue: ['#3b82f6', '#dbeafe', '#1d4ed8'],
  amber: ['#f59e0b', '#fef3c7', '#b45309'],
  green: ['#22c55e', '#dcfce7', '#15803d'],
  pink: ['#ec4899', '#fce7f3', '#be185d'],
}

/** Vita: a heart in a sweatband who curls dumbbells while working. */
function Vita({ face, working }: ArtProps) {
  const heart = 'M50 90 C22 72 8 54 12 37 C16 22 36 17 50 32 C64 17 84 22 88 37 C92 54 78 72 50 90Z'
  return (
    <>
      <defs>
        <linearGradient id="vita-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#fb7185" /><stop offset="1" stopColor="#e11d48" /></linearGradient>
        <clipPath id="vita-clip"><path d={heart} /></clipPath>
      </defs>
      {[{ x: 14, side: 'l' }, { x: 86, side: 'r' }].map(({ x, side }) => (
        <g key={side} className={`m-curl m-curl-${side}`} style={{ transformOrigin: `${x}px 58px` }}>
          <path d={`M${x} 58 L${side === 'l' ? x - 8 : x + 8} 68`} stroke="#be123c" strokeWidth="5" strokeLinecap="round" />
          {working && (
            <g>
              <rect x={(side === 'l' ? x - 8 : x + 8) - 9} y="66" width="18" height="4" rx="2" fill="#475569" />
              <rect x={(side === 'l' ? x - 8 : x + 8) - 11} y="62" width="5" height="12" rx="2" fill="#334155" />
              <rect x={(side === 'l' ? x - 8 : x + 8) + 6} y="62" width="5" height="12" rx="2" fill="#334155" />
            </g>
          )}
        </g>
      ))}
      <g className="m-beat">
        <path d={heart} fill="url(#vita-b)" />
        {/* Sweatband */}
        <g clipPath="url(#vita-clip)">
          <rect x="0" y="27" width="100" height="9" fill="white" />
          <rect x="0" y="30" width="100" height="3" fill="#e11d48" />
        </g>
        <ellipse cx="28" cy="44" rx="7" ry="4" fill="white" opacity="0.4" transform="rotate(-30 28 44)" />
        <Eyes x1={39} x2={61} y={50} r={8.5} face={face} />
        <Cheeks x1={28} x2={72} y={61} r={4.5} />
        <Mouth x={50} y={63} w={12} face={face} />
      </g>
      {working && <path className="m-sweat" d="M84 26 q4 6 0 9 q-4 -3 0 -9z" fill="#7dd3fc" stroke="#0284c7" strokeWidth="1" />}
    </>
  )
}

/** Kai: a piggy bank whose eyes turn to dollar signs as coins drop in. */
function Kai({ face, working }: ArtProps) {
  return (
    <>
      <defs>
        <linearGradient id="kai-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#6ee7b7" /><stop offset="1" stopColor="#059669" /></linearGradient>
      </defs>
      {working && [0, 1, 2].map(i => (
        <g key={i} className="m-coin" style={{ animationDelay: `${i * 0.45}s` }}>
          <circle cx="50" cy="8" r="7" fill="#fbbf24" stroke="#b45309" strokeWidth="1.5" />
          <text x="50" y="12" textAnchor="middle" fontSize="10" fontWeight="900" fill="#92400e">$</text>
        </g>
      ))}
      <g className="m-jiggle">
        {/* Legs */}
        {[26, 40, 60, 74].map(x => <rect key={x} x={x - 5} y="78" width="10" height="13" rx="4" fill="#047857" />)}
        {/* Curly tail */}
        <path className="m-tail" d="M86 56 c8 -2 8 -10 2 -9 c-5 1 -2 8 4 6" fill="none" stroke="#059669" strokeWidth="3" strokeLinecap="round" />
        {/* Ears */}
        <path d="M24 38 L28 18 L42 32Z" fill="#10b981" stroke="#047857" strokeWidth="2" strokeLinejoin="round" />
        <path d="M76 38 L72 18 L58 32Z" fill="#10b981" stroke="#047857" strokeWidth="2" strokeLinejoin="round" />
        <ellipse cx="50" cy="58" rx="38" ry="29" fill="url(#kai-b)" />
        <rect x="42" y="31" width="16" height="4" rx="2" fill="#065f46" />
        <ellipse cx="32" cy="44" rx="8" ry="4" fill="white" opacity="0.4" transform="rotate(-25 32 44)" />
        <Eyes x1={37} x2={63} y={50} r={8.5} face={face} dollar={working && face.mood !== 'sleep' && face.mood !== 'happy'} />
        <Cheeks x1={25} x2={75} y={64} r={5} />
        {/* Snout */}
        <ellipse cx="50" cy="66" rx="11" ry="8" fill="#a7f3d0" stroke="#047857" strokeWidth="2" />
        <ellipse cx="46" cy="66" rx="2" ry="3" fill="#065f46" />
        <ellipse cx="54" cy="66" rx="2" ry="3" fill="#065f46" />
        <Mouth x={50} y={77} w={9} face={face} />
      </g>
    </>
  )
}

/** Cato: a bespectacled envelope who scribbles and fires off paper planes. */
function Cato({ face, working }: ArtProps) {
  return (
    <>
      <defs>
        <linearGradient id="cato-b" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#93c5fd" /><stop offset="1" stopColor="#4f46e5" /></linearGradient>
      </defs>
      {working && [0, 1].map(i => (
        <g key={i} className="m-plane" style={{ animationDelay: `${i * 0.8}s` }}>
          <path d="M50 30 L72 22 L58 36 Z M58 36 L62 30" fill="white" stroke="#4338ca" strokeWidth="1.5" strokeLinejoin="round" />
        </g>
      ))}
      <g className="m-squish">
        {/* Feet */}
        <ellipse cx="38" cy="90" rx="8" ry="4" fill="#312e81" />
        <ellipse cx="62" cy="90" rx="8" ry="4" fill="#312e81" />
        <rect x="12" y="28" width="76" height="60" rx="9" fill="url(#cato-b)" />
        {/* The flap: flaps open and shut while Cato is busy */}
        <g className="m-flap">
          <path d="M14 31 L50 54 L86 31 Q86 29 83 29 H17 Q14 29 14 31Z" fill="#6366f1" stroke="#c7d2fe" strokeWidth="2" strokeLinejoin="round" />
        </g>
        <Eyes x1={37} x2={63} y={64} r={8} face={face} />
        {/* Glasses */}
        <g fill="none" stroke="#1e1b4b" strokeWidth="2.5">
          <circle cx="37" cy="64" r="11" />
          <circle cx="63" cy="64" r="11" />
          <path d="M48 63 Q50 60 52 63" />
        </g>
        <Mouth x={50} y={79} w={10} face={face} />
        {/* Bow tie */}
        <path d="M42 86 L50 89 L58 86 L58 93 L50 90 L42 93Z" fill="#f43f5e" />
      </g>
      {/* Pencil, scribbling away while working */}
      <g className="m-pencil" style={{ transformOrigin: '92px 70px' }}>
        <path d="M84 78 L98 52" stroke="#f59e0b" strokeWidth="5" strokeLinecap="round" />
        <path d="M84 78 L82 83 L87 80Z" fill="#1f2937" />
      </g>
    </>
  )
}

/** Any other agent: a little robot whose antenna gear spins while it works. */
function Robo({ face, colour }: ArtProps) {
  const [body, screen, trim] = AVATAR_COLOURS[colour ?? 'purple'] ?? AVATAR_COLOURS.purple
  return (
    <>
      <g className="m-antenna">
        <path d="M50 26 V12" stroke={trim} strokeWidth="3.5" strokeLinecap="round" />
        <circle className="m-bulb" cx="50" cy="9" r="5" fill="#f472b6" stroke={trim} strokeWidth="2" />
      </g>
      <g className="m-squish">
        <rect x="14" y="26" width="72" height="62" rx="16" fill={body} />
        <rect x="20" y="32" width="60" height="44" rx="11" fill={screen} />
        <Eyes x1={38} x2={62} y={52} r={8.5} face={face} />
        <Mouth x={50} y={66} w={11} face={face} />
        <rect x="8" y="50" width="6" height="16" rx="3" fill={trim} />
        <rect x="86" y="50" width="6" height="16" rx="3" fill={trim} />
      </g>
    </>
  )
}

const ART: Record<string, (p: ArtProps) => JSX.Element> = {
  health: Vita,
  finance: Kai,
  professional: Cato,
}
