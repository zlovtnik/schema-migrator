# --- build frontend ---
FROM oven/bun:1.1 AS ui-builder

WORKDIR /ui
RUN chown bun:bun /ui
USER bun

COPY --chown=bun:bun schema-migrator-ui/package.json schema-migrator-ui/pnpm-lock.yaml ./
RUN bun install --frozen-lockfile

COPY --chown=bun:bun schema-migrator-ui/ .
RUN bun run build

# --- nginx with static files baked in ---
FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf
COPY nginx/nginx.conf /etc/nginx/conf.d/app.conf
COPY --from=ui-builder /ui/dist /usr/share/nginx/html
