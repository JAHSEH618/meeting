# syntax=docker/dockerfile:1.6

# ----- build stage -----------------------------------------------------------
FROM node:20-alpine AS build
WORKDIR /src

# Cache npm deps when only source changes.
COPY package.json package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci; else npm install; fi

# Vite reads the contracts package to generate types.gen.ts at boot
# (codegen target), so we have to bring it into the build image even
# though it lives outside apps/meeting-web. The compose / CI builder
# is expected to set the build context to the repo root and use this
# Dockerfile with `--file apps/meeting-web/Dockerfile`. For a per-app
# build context, copy types.gen.ts into the source tree before invoking
# docker build instead.
COPY . .

# Production build.
RUN npm run build

# ----- runtime stage ---------------------------------------------------------
FROM nginx:1.27-alpine

# CSP / SPA fallback / gzip / cache headers all live in nginx.conf.
COPY --from=build /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Run as the non-root nginx user (added by the upstream image).
USER nginx

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --spider -q http://localhost/healthz || exit 1
