import { type ReactNode } from 'react'
import { Bot, Shield, Workflow } from 'lucide-react'
import { Logo } from './Sidebar'
import { ThemeSwitcher } from '@/components/theme/ThemeSwitcher'

const highlights = [
  { icon: Bot, text: 'Agents that take work off your plate' },
  { icon: Workflow, text: 'Workflows that learn from what you repeat' },
  { icon: Shield, text: 'You decide what runs on its own' },
]

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="app-bg flex min-h-screen">
      {/* Brand panel, large screens only */}
      <div className="relative hidden w-[44%] overflow-hidden brand-gradient p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="pointer-events-none absolute -right-24 -top-24 h-96 w-96 rounded-full bg-white/10 blur-3xl" />
        <div className="pointer-events-none absolute -bottom-32 -left-16 h-96 w-96 rounded-full bg-black/10 blur-3xl" />
        <div className="relative flex items-center gap-2.5">
          <div className="grid h-9 w-9 place-items-center rounded-lg bg-white/20 font-bold backdrop-blur">J</div>
          <span className="text-xl font-bold tracking-tight">Jizza</span>
        </div>
        <div className="relative">
          <h2 className="max-w-md text-3xl font-semibold leading-tight">Your personal team of AI agents.</h2>
          <ul className="mt-8 space-y-4">
            {highlights.map(({ icon: Icon, text }, i) => (
              <li key={text} className="flex items-center gap-3 animate-fade-in-up" style={{ animationDelay: `${i * 80}ms` }}>
                <span className="grid h-9 w-9 place-items-center rounded-lg bg-white/15 backdrop-blur">
                  <Icon className="h-4 w-4" />
                </span>
                <span className="text-sm text-white/90">{text}</span>
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
