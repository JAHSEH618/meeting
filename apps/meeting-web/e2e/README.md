# meeting-web E2E suite

Playwright covers the Phase 8.7 golden path:

* Login → create meeting → transcript / exports pages render.

## Run locally

```bash
docker compose -f infra/meeting-infra/docker/compose/docker-compose.yml \
    --profile full-stack up -d --build

cd apps/meeting-web
npm install
npm run e2e:install     # one-time: pulls the chromium binary
npm run e2e
```

Tests assume the meeting-api dev auth account is available (`admin` / `admin123`).
Override with `E2E_USER` / `E2E_PASS` / `E2E_BASE_URL` when running
against staging.

## CI hook

`.github/workflows/ci.yml` runs the suite in the `meeting-web-e2e`
job — bring the compose stack up, then `npm run e2e`. The Playwright
report is uploaded as a build artifact on failure.
