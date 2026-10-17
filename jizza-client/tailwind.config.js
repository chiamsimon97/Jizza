/** @type {import('tailwindcss').Config} */
const scale = (name) =>
  Object.fromEntries(
    [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950].map(step => [
      step,
      `rgb(var(--${name}-${step}) / <alpha-value>)`,
    ]),
  )

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      // Both scales are driven by CSS variables so every existing gray-*/brand-*
      // class follows the active theme (see src/index.css).
      colors: {
        gray: scale('gray'),
        brand: scale('brand'),
        accent: scale('accent'),
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
      boxShadow: {
        glow: '0 0 0 1px rgb(var(--brand-500) / 0.15), 0 8px 24px -8px rgb(var(--brand-500) / 0.35)',
      },
      keyframes: {
        'fade-in-up': {
          from: { opacity: '0', transform: 'translateY(6px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'fade-in': { from: { opacity: '0' }, to: { opacity: '1' } },
        'slide-in-left': { from: { transform: 'translateX(-100%)' }, to: { transform: 'translateX(0)' } },
        'slide-in-right': { from: { transform: 'translateX(100%)' }, to: { transform: 'translateX(0)' } },
        'orb-pulse': {
          '0%, 100%': { transform: 'scale(1)', opacity: '0.35' },
          '50%': { transform: 'scale(1.15)', opacity: '0.6' },
        },
        'jizza-filled': {
          '0%': { boxShadow: '0 0 0 0 rgb(var(--brand-500) / 0.55)' },
          '100%': { boxShadow: '0 0 0 8px rgb(var(--brand-500) / 0)' },
        },
        'typing': {
          '0%, 80%, 100%': { opacity: '0.25', transform: 'translateY(0)' },
          '40%': { opacity: '1', transform: 'translateY(-2px)' },
        },
      },
      animation: {
        'fade-in-up': 'fade-in-up 0.3s ease-out both',
        'fade-in': 'fade-in 0.2s ease-out both',
        'slide-in-left': 'slide-in-left 0.25s ease-out both',
        'slide-in-right': 'slide-in-right 0.3s cubic-bezier(0.16, 1, 0.3, 1) both',
        'typing': 'typing 1.2s ease-in-out infinite',
        'orb-pulse': 'orb-pulse 3.2s ease-in-out infinite',
        'orb-pulse-fast': 'orb-pulse 1s ease-in-out infinite',
        'spin-slow': 'spin 2.4s linear infinite',
        'jizza-filled': 'jizza-filled 1.2s ease-out 2',
      },
    },
  },
  plugins: [],
}
