# --- build frontend ---
FROM oven/bun:1.2 AS ui-builder

WORKDIR /ui
RUN chown bun:bun /ui
USER bun

ARG VITE_KEYCLOAK_URL=
ARG VITE_KEYCLOAK_REALM=
ARG VITE_KEYCLOAK_CLIENT_ID=
ARG VITE_KEYCLOAK_REDIRECT_URI=
ARG VITE_KEYCLOAK_DIRECT_ACCESS_GRANTS=false

ENV VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL
ENV VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM
ENV VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID
ENV VITE_KEYCLOAK_REDIRECT_URI=$VITE_KEYCLOAK_REDIRECT_URI
ENV VITE_KEYCLOAK_DIRECT_ACCESS_GRANTS=$VITE_KEYCLOAK_DIRECT_ACCESS_GRANTS

COPY --chown=bun:bun schema-migrator-ui/package.json schema-migrator-ui/bun.lock ./
RUN bun install --frozen-lockfile

COPY --chown=bun:bun schema-migrator-ui/ .
RUN bun run build

# --- static nginx with SPA assets baked in ---
FROM nginx:1.27-alpine

RUN rm /etc/nginx/conf.d/default.conf \
  && chown -R nginx:nginx /var/cache/nginx /var/run /usr/share/nginx/html /etc/nginx/conf.d
COPY frontend/spa.conf.template /etc/nginx/conf.d/app.conf
COPY --from=ui-builder /ui/dist /usr/share/nginx/html

EXPOSE 8080
USER nginx
ENTRYPOINT ["nginx", "-g", "daemon off;"]
