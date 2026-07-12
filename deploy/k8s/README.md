# Schema Migrator Kubernetes Stack

This directory defines the Kubernetes replacement stack for the schema migrator UI/API:

- one public `LoadBalancer` service, `schema-migrator-edge`, managed by OCI/OKE
- Traefik routing `/api` to the backend and `/` to the UI
- Keycloak on port `8080` with the existing `middleware` realm and `bedrock-ui` client
- MongoDB as an internal persistence dependency

Before applying, replace the placeholders in `secret.template.yaml` and set the public origin in
`stack.yaml` after OCI assigns the new load balancer address:

```bash
kubectl apply -f namespace.yaml
kubectl apply -f secret.template.yaml
kubectl apply -f stack.yaml
kubectl -n schema-migrator get svc schema-migrator-edge -w
```

Once `EXTERNAL-IP` is assigned, update these values and roll the affected deployments:

- `BEDROCK_CORS_ORIGINS`
- `BEDROCK_KEYCLOAK_ISSUER`
- `VITE_KEYCLOAK_URL`
- `VITE_KEYCLOAK_REDIRECT_URI`

The deployment requires an HTTPS hostname backed by a valid load-balancer or
Traefik certificate. Replace the plaintext IP example below with your assigned
`https://` origin (e.g. `https://schema.example.com`) and reuse that single
origin for the issuer, UI URL, and redirect URI:

```text
BEDROCK_KEYCLOAK_ISSUER=https://schema.example.com/realms/middleware
VITE_KEYCLOAK_URL=https://schema.example.com
VITE_KEYCLOAK_REDIRECT_URI=https://schema.example.com/callback
```

The backend reads Keycloak keys through the internal `BEDROCK_KEYCLOAK_JWKS_URI`, so only the issuer
must match the browser-facing token issuer.
