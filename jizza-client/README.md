# Jizza Client

React + Vite frontend for the Jizza AI Agent Platform.

## Stack

- **React 18** + **TypeScript**
- **Vite 5** (dev server with proxy to backend)
- **Tailwind CSS v3** (custom `brand-500` palette)
- **TanStack Query v5** (data fetching)
- **Zustand v5** (auth state with `persist`)
- **React Router v6**
- **@stomp/stompjs** + **SockJS** (real-time streaming)
- **Lucide React** (icons)

## Getting started

```bash
pnpm install
cp .env.example .env
pnpm dev
```

Expects the backend running at `http://localhost:8080`. The Vite proxy forwards `/api` and `/ws` automatically.

## Auth

Login at `/login`. MFA verification at `/mfa`. All API calls use session cookies with CSRF protection (`X-XSRF-TOKEN` header read from the `XSRF-TOKEN` cookie).

## Real-time chat

Chat messages stream via STOMP WebSocket (`/ws` → SockJS). Chunks arrive on `/user/queue/chat`. Incognito mode skips persistence entirely.

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Backend base URL |
