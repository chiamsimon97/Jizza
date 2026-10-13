import { type ReactNode, useState } from 'react'
import { Logo } from './Sidebar'
import { JizzaLogo } from '@/components/brand/JizzaLogo'
import { AgentLogo } from '@/components/brand/AgentLogo'
import { JizzaOrb } from '@/jizza/JizzaOrb'
import { ThemeSwitcher } from '@/components/theme/ThemeSwitcher'

const team = [
  { key: 'health', name: 'Vita', role: 'Health' },
  { key: 'finance', name: 'Kai', role: 'Money' },
  { key: 'professional', name: 'Cato', role: 'Work' },
]

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="app-bg flex min-h-screen">
      {/* Brand panel, large screens only */}
      <div className="relative hidden w-[44%] overflow-hidden brand-gradient p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="pointer-events-none absolute -right-24 -top-24 h-96 w-96 rounded-full bg-white/10 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-32 -left-16 h-96 w-96 rounded-full bg-black/10 blur-3xl" />
        <div className="relative">
          <JizzaLogo inverted size={40} />
        </div>
        <div className="relative">
          <JizzaOrb size={112} className="mb-8 drop-shadow-2xl" />
          <h2 className="max-w-md text-3xl font-semibold leading-tight">Hi, I'm Jizza. Your personal assistant, with a team behind me.</h2>
          <ul className="mt-8 flex gap-6">
            {team.map(({ key, name, role }, i) => (
              <li key={key} className="group flex flex-col items-center gap-2 animate-fade-in-up" style={{ animationDelay: `${i * 90}ms` }}>
                <span className="rounded-2xl bg-white/15 p-1.5 backdrop-blur transition-transform group-hover:-translate-y-1">
                  <AgentWithHover agentKey={key} />
                </span>
                <span className="text-sm font-medium">{name}</span>
                <span className="text-xs text-white/70">{role}</span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-white/60">© Jizza</p>
      </div>

      <div className="relative flex flex-1 flex-col items-center justify-center px-4 py-12">
        <div className="absolute right-4 top-4 w-60">
          <ThemeSwitcher />
        </div>
        <div className="mb-8 lg:hidden"><Logo /></div>
        <div className="w-full max-w-sm rounded-2xl border border-gray-200 bg-white p-8 shadow-xl shadow-gray-900/5 animate-fade-in-up dark:border-gray-800 dark:bg-gray-900 dark:shadow-black/20">
          {children}
        </div>
      </div>
    </div>
  )
}

/** Each teammate acts out its job while you hover it. */
function AgentWithHover({ agentKey }: { agentKey: string }) {
  const [hover, setHover] = useState(false)
  return (
    <span onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}>
      <AgentLogo agentKey={agentKey} size={52} state={hover ? 'working' : 'idle'} />
    </span>
  )
}
