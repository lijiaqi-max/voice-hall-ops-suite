FROM node:24-alpine AS web
WORKDIR /src
RUN corepack enable
COPY web-admin/package.json web-admin/pnpm-lock.yaml web-admin/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY web-admin/ ./
RUN pnpm build

FROM caddy:2.10-alpine
COPY infra/Caddyfile /etc/caddy/Caddyfile
COPY --from=web /src/dist /srv

