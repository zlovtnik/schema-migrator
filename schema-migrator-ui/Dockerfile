FROM oven/bun:1.1 AS builder

WORKDIR /app
RUN chown bun:bun /app
USER bun

COPY --chown=bun:bun package.json pnpm-lock.yaml ./
# bun can install from pnpm lockfile directly
RUN bun install --frozen-lockfile

COPY --chown=bun:bun . .
RUN bun run build

# output stage - hand-off to nginx
FROM scratch AS dist
COPY --from=builder /app/dist /dist
