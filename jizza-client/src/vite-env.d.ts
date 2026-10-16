/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string
  /** Minutes without activity before the app signs out on its own. 0 turns it off. Default 15. */
  readonly VITE_IDLE_SIGN_OUT_MINUTES?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
