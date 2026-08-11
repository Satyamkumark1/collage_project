# Deployment — free tier

One Docker image (frontend bundled into the backend jar, see root `Dockerfile`) on Render's free
web service, talking to free-tier Neon (Postgres + pgvector), Supabase Storage, and Upstash
(Redis). Groq and Voyage AI stay external, same as local dev. See `docs/DECISIONS.md` for why
this shape was chosen over the alternatives.

## 1. Neon — database

1. Create a project at neon.tech (free tier). Note the connection string it gives you.
2. Split it into `DB_URL` (`jdbc:postgresql://<host>/<db>?sslmode=require`), `DB_USER`,
   `DB_PASSWORD`.
3. Nothing else to do here — `pgvector` ships as an available extension on Neon, and
   `V6.1__pgvector_extension_early.sql` already runs `CREATE EXTENSION IF NOT EXISTS vector` on
   boot. Flyway (`ddl-auto: validate`, `flyway.enabled: true`) creates the rest of the schema on
   first boot against this database.

## 2. Supabase — file storage

Local disk (`STORAGE_LOCAL_ROOT`) doesn't survive a redeploy on any free host — Render's free
disk is ephemeral. `SupabaseStorageProvider` is the persistent alternative.

1. In your Supabase account, create a project (or reuse an existing one — it's just object
   storage, doesn't need to be dedicated).
2. Storage → new bucket, **private**. Name it whatever you set `SUPABASE_STORAGE_BUCKET` to
   (default `studyflow-documents`).
3. Settings → API: copy the **Project URL** (`SUPABASE_URL`) and the **`service_role`** secret
   key (`SUPABASE_SERVICE_KEY` — not the `anon` key; the service role key is what lets the
   backend read/write without per-user RLS policies).
4. Set `STORAGE_PROVIDER=supabase`.

## 3. Upstash — Redis (login-lockout L2)

Reuse the Upstash database from local dev, or create a separate one for prod — either works,
keys are namespaced (`login:lock:{sha256}`). `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`
from its REST API tab.

## 4. Groq + Voyage AI

Reuse the same keys as local dev, or issue separate ones if you want prod usage tracked
separately. `GROQ_API_KEY` (optionally `GROQ_API_KEYS` — comma-separated pool), `VOYAGE_API_KEY`.

## 5. Google / GitHub OAuth apps

Add the production redirect URI to each app's existing console entry (same app as local dev
works — most providers allow multiple redirect URIs, no need for a second app):

- Google Cloud Console → Credentials → your OAuth client → Authorized redirect URIs → add
  `https://<your-render-domain>/login/oauth2/code/google`
- GitHub → Settings → Developer settings → OAuth Apps → your app → add
  `https://<your-render-domain>/login/oauth2/code/github`

(Spring Security's default redirect template is `{baseUrl}/login/oauth2/code/{registrationId}` —
no custom override in this codebase, so those are the exact paths.)

## 6. Render — compute

1. New → Web Service → connect this repo. Render auto-detects the root `Dockerfile`; no build
   command needed.
2. Free instance type. Set the environment variables below.
3. Deploy. First boot runs Flyway migrations against Neon.

### Environment variables to set on Render

| Variable | Value |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | from Neon |
| `JWT_SECRET` | new secret, **don't reuse the dev one** — `openssl rand -base64 64 \| tr -d '\n'` |
| `CORS_ALLOWED_ORIGIN` | your Render URL itself, e.g. `https://studyflow.onrender.com` (frontend and backend share this origin — see `docs/DECISIONS.md`) |
| `STORAGE_PROVIDER` | `supabase` |
| `SUPABASE_URL`, `SUPABASE_SERVICE_KEY`, `SUPABASE_STORAGE_BUCKET` | from Supabase |
| `UPSTASH_REDIS_REST_URL`, `UPSTASH_REDIS_REST_TOKEN` | from Upstash |
| `GROQ_API_KEY` (or `GROQ_API_KEYS`), `VOYAGE_API_KEY` | from Groq / Voyage |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | from Google Cloud Console |
| `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET` | from GitHub OAuth App |

`PORT` is set by Render automatically — `application.yml` already binds `server.port` to it. No
`STORAGE_LOCAL_ROOT` needed in prod (only read when `STORAGE_PROVIDER=local`, the local-dev
default). No `spring.profiles.active=local` either — that profile's relaxed cookie settings
(`secure: false`, `same-site: Lax`) are a local-only deviation for plain `http://localhost`; prod
runs the base config's `secure: true` / `same-site: Strict` as-is, which works here because
frontend and backend share one origin.

## Known free-tier limits

- **Render free web service** spins down after 15 minutes idle — first request after that takes
  ~30–50s (cold start), not a bug.
- **Voyage AI**: 3 requests/min / 10K tokens/min hard cap (no payment method on this account —
  same constraint already documented for local dev in `docs/DECISIONS.md`).
- **Neon / Upstash / Supabase free tiers**: each has its own storage/bandwidth/request ceiling —
  fine for a demo, not for real traffic.
