/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  /** Minutes without activity before the app signs out on its own. 0 or unset turns it off (the default); the server's 3-day idle timeout still applies. */
  readonly VITE_IDLE_SIGN_OUT_MINUTES?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
