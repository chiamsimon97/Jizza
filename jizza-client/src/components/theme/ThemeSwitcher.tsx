import { Sun, Moon, Sunset, type LucideIcon } from 'lucide-react'
import { THEMES, useThemeStore, type Theme } from '@/store/themeStore'

const icons: Record<Theme, LucideIcon> = { light: Sun, dark: Moon, sunset: Sunset }

interface ThemeSwitcherProps {
  /** Icon-only button that cycles through themes, for tight spaces. */
  compact?: boolean
  className?: string
}

export function ThemeSwitcher({ compact, className = '' }: ThemeSwitcherProps) {
  const { theme, setTheme, cycleTheme } = useThemeStore()

  if (compact) {
    const Icon = icons[theme]
    const next = THEMES[(THEMES.findIndex(t => t.id === theme) + 1) % THEMES.length]
    return (
      <button
        type="button"
        onClick={cycleTheme}
        title={`Switch to ${next.label} theme`}
        aria-label={`Switch to ${next.label} theme`}
        className={`grid h-9 w-9 place-items-center rounded-lg text-gray-500 transition-all hover:bg-gray-100 hover:text-gray-900 active:scale-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100 ${className}`}
      >
        <Icon key={theme} className="h-4 w-4 animate-fade-in" />
      </button>
    )
  }

  const activeIndex = THEMES.findIndex(t => t.id === theme)

  return (
    <div
      role="radiogroup"
      aria-label="Theme"
      className={`relative grid grid-cols-3 rounded-xl border border-gray-200 bg-gray-100 p-1 dark:border-gray-700 dark:bg-gray-800 ${className}`}
    >
      {/* Sliding highlight behind the active option */}
      <span
        aria-hidden
        className="absolute inset-y-1 left-1 rounded-lg bg-white shadow-sm transition-transform duration-300 ease-out dark:bg-gray-700"
        style={{ width: 'calc((100% - 0.5rem) / 3)', transform: `translateX(${activeIndex * 100}%)` }}
      />
      {THEMES.map(({ id, label }) => {
        const Icon = icons[id]
        const active = id === theme
        return (
          <button
            key={id}
            type="button"
            role="radio"
            aria-checked={active}
            title={`${label} theme`}
            onClick={() => setTheme(id)}
            className={`relative z-10 flex items-center justify-center gap-1.5 rounded-lg px-2 py-1.5 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 ${
              active
                ? 'text-brand-600 dark:text-brand-300'
                : 'text-gray-500 hover:text-gray-900 dark:text-gray-400 dark:hover:text-gray-100'
            }`}
          >
            <Icon className="h-3.5 w-3.5" />
            <span>{label}</span>
          </button>
        )
      })}
    </div>
  )
}
