/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: {
          50:  '#f0f4ff',
          100: '#dde7ff',
          200: '#b3c7ff',
          300: '#7a9ef8',
          400: '#4c77f0',
          500: '#2952e3',
          600: '#1e3fc0',
          700: '#182f99',
          800: '#162678',
          900: '#141f5e',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'Fira Code', 'monospace'],
      },
    },
  },
  plugins: [],
}
