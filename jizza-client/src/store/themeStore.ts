import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type Theme = 'light' | 'dark' | 'sunset'

export const THEMES: { id: Theme; label: string }[] = [
  { id: 'light', label: 'Light' },
  { id: 'dark', label: 'Dark' },
  { id: 'sunset', label: 'Sunset' },
]

const STORAGE_KEY = 'jizza-theme'

function systemTheme(): Theme {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches
    ? 'dark'
    : 'light'
}

export function applyTheme(theme: Theme) {
  const root = document.documentElement
  root.dataset.theme = theme
  root.classList.toggle('dark', theme === 'dark')
}

interface ThemeState {
  theme: Theme
  setTheme: (theme: Theme) => void
  cycleTheme: () => void
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      theme: systemTheme(),
      setTheme: (theme) => {
        // Briefly enable colour transitions so the switch fades instead of snapping.
        const root = document.documentElement
        root.classList.add('theme-transition')
        applyTheme(theme)
        window.setTimeout(() => root.classList.remove('theme-transition'), 300)
        set({ theme })
      },
      cycleTheme: () => {
        const ids = THEMES.map(t => t.id)
        get().setTheme(ids[(ids.indexOf(get().theme) + 1) % ids.length])
      },
    }),
    {
      name: STORAGE_KEY,
      onRehydrateStorage: () => (state) => {
        if (state) applyTheme(state.theme)
      },
    },
  ),
)

// Apply whatever is current right away (covers a first visit with nothing stored).
applyTheme(useThemeStore.getState().theme)
