# ui-web — JustSearch Frontend

React + TypeScript + Vite frontend for JustSearch.

Architecture: `docs/explanation/07-ui-host-architecture.md`
Design system: `docs/explanation/10-ui-ux-design.md`

## Scripts

| Command | What it does |
|---------|-------------|
| `npm run dev` | Vite dev server (needs backend running) |
| `npm run dev:mock` | Vite with MSW mock mode (**no backend needed**) |
| `npm run build` | Production build |
| `npm run test` | Playwright E2E tests (Desktop project) |
| `npm run test:gate` | Playwright gate smoke test |
| `npm run test:unit` | Vitest unit tests |
| `npm run typecheck` | TypeScript type checking |
| `npm run lint` | ESLint |
| `npm run build:analyze` | Production build with bundle visualizer |

## Development without a backend (MSW mock mode)

```bash
npm run dev:mock
# or: VITE_MSW=true npm run dev
```

This starts the Vite dev server with [Mock Service Worker](https://mswjs.io/)
intercepting all API calls. The UI loads with fixture data — no Head, no
Worker, no JVM needed.

**When to use:** UI component work, styling, layout changes, accessibility
improvements — anything that doesn't need real search results.

**How it works:** MSW registers a service worker that intercepts `fetch()`
at the network level. The app code is unchanged — `src/api/http.ts` makes
real fetch calls, MSW returns fixture responses before they hit the network.

**Mock handlers:** `src/mocks/handlers.ts` — defines responses for:
- `/api/status` — connection + readiness
- `/api/settings/v2` — app settings
- `/api/indexing/roots` — watched folders
- `/api/inference/status` — AI runtime status
- `/api/knowledge/search` — 3 fixture search results
- `/api/preview` — mock document preview
- `POST /api/ui/ready` — boot handshake

**Adding a new mock handler:** If you add a new API endpoint, add a handler
in `src/mocks/handlers.ts`:
```typescript
import { http, HttpResponse } from 'msw'
export const handlers = [
  // ... existing handlers ...
  http.get('/api/your-endpoint', () => HttpResponse.json({ data: 'fixture' })),
]
```

## Development with a real backend

```bash
npm run dev
# Backend must be running (default port 33221, or set VITE_JUSTSEARCH_API_PORT)
```

The Vite proxy forwards all `/api/*` requests to the backend. Port is
configurable via `VITE_JUSTSEARCH_API_PORT` or `VITE_API_PORT` env vars.

## Demo mode

Open `http://localhost:5173?demo=true` — the UI renders with hardcoded
fixture data and built-in SSE simulation. No backend, no MSW needed. Simpler
than MSW mode but less realistic (no network-level interception).

## Visual verification with Chrome extension

Agents can use Claude Code's Chrome extension (`/chrome`) to visually inspect
the UI during development:
1. Start `npm run dev:mock` (or `npm run dev` with backend)
2. Enable Chrome: `/chrome` in the Claude Code session
3. Navigate to `http://localhost:5173`
4. Take screenshots, click elements, verify layouts

## Source structure

```
src/
  api/           — HTTP client, SSE streams, API domain modules
  app/           — App-level components
  components/    — UI components (shell, zones, search, preview, etc.)
  hooks/         — React hooks (useSearch, useApiConnection, etc.)
  mocks/         — MSW mock handlers and fixtures
  stores/        — Zustand state stores
  styles/        — CSS tokens and global styles
  utils/         — Shared utilities
```

## Testing

**Unit tests** (Vitest):
```bash
npm run test:unit        # watch mode
npm run test:unit:run    # single run
```

**E2E tests** (Playwright):
```bash
npm run test             # Desktop project
npm run test:gate        # Gate smoke test only
npm run test:all         # All projects
```

**Visual regression**:
```bash
npm run visual:diff                 # Compare against baseline
npm run visual:update-baseline      # Update baseline screenshots
```

**Evidence capture** (screenshots + a11y scans):
```bash
npm run capture:evidence -- --scenario ui_screenshots --api-base-url demo
```

See `docs/how-to/capture-ui-evidence.md` for details.
