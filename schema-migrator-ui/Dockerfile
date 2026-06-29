FROM oven/bun:1.1

WORKDIR /app

RUN chown bun:bun /app
COPY --chown=bun:bun . .
USER bun
RUN if [ -f bun.lock ] || [ -f bun.lockb ]; then bun install --frozen-lockfile; else bun install; fi

EXPOSE 5173

CMD ["bun", "run", "dev", "--host", "0.0.0.0"]
