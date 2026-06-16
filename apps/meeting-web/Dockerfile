# syntax=docker/dockerfile:1.6

# ----- build stage -----------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /src

# Build context MUST be the repo root (see deploy/DEPLOY.md §4.2):
#   docker build -f apps/meeting-web/Dockerfile -t meeting-web:dev .
# because `npm run build` triggers `npm run codegen` which reads
#   ../../packages/meeting-contracts/openapi/public-api.yaml
# relative to apps/meeting-web/. Copying just apps/meeting-web/ would
# leave codegen with no contracts on disk.
#
# Cache npm deps when only source changes.
COPY apps/meeting-web/package.json apps/meeting-web/package-lock.json* ./apps/meeting-web/
COPY packages/meeting-contracts ./packages/meeting-contracts
WORKDIR /src/apps/meeting-web
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

# Bring in the app sources after the deps install so a source-only
# change doesn't bust the npm cache layer.
COPY apps/meeting-web ./

# Production build (includes codegen → src/shared/api/types.gen.ts).
RUN npm run build

# ----- runtime stage ---------------------------------------------------------
FROM nginx:1.27-alpine

# CSP / SPA fallback / gzip / cache headers all live in nginx.conf.
COPY --from=build /src/apps/meeting-web/dist /usr/share/nginx/html
COPY apps/meeting-web/nginx.conf /etc/nginx/conf.d/default.conf

# Create cache directories with proper permissions before switching to nginx user
RUN mkdir -p /var/cache/nginx/client_temp /var/cache/nginx/proxy_temp \
             /var/cache/nginx/fastcgi_temp /var/cache/nginx/uwsgi_temp \
             /var/cache/nginx/scgi_temp && \
    chown -R nginx:nginx /var/cache/nginx && \
    chown -R nginx:nginx /usr/share/nginx/html

# Run as the non-root nginx user (added by the upstream image).
USER nginx

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --spider -q http://localhost/healthz || exit 1
