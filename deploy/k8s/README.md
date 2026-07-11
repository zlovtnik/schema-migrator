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

For an OCI load balancer IP of `203.0.113.10`, use:

```text
BEDROCK_KEYCLOAK_ISSUER=http://203.0.113.10:8080/realms/middleware
VITE_KEYCLOAK_URL=http://203.0.113.10:8080
VITE_KEYCLOAK_REDIRECT_URI=http://203.0.113.10/callback
```

The backend reads Keycloak keys through the internal `BEDROCK_KEYCLOAK_JWKS_URI`, so only the issuer
must match the browser-facing token issuer.
