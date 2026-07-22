# Schema Migrator Kubernetes Stack

This standalone stack deploys only the schema-migrator backend, UI, and edge router. It does not deploy PostgreSQL, MongoDB, TiDB, or Keycloak.

Before applying it:

1. Provision an external TiDB v8.5+ `schema_migrator` database and dedicated non-root application user.
2. Apply the canonical `sql/tidb/schema_migrator` migrations with the repository provisioning schema job and its separate DDL credential. The application never creates or migrates its own tables.
3. Create a PKCS12 truststore containing the TiDB server CA. Put its base64 value and password in `secret.template.yaml`.
4. Set a JDBC URL ending in `/schema_migrator` with `sslMode=VERIFY_IDENTITY`, plus the external OIDC issuer/JWKS values.
5. Replace the pinned image and public-origin placeholders in `stack.yaml`.

```bash
kubectl apply -f namespace.yaml
kubectl apply -f secret.template.yaml
kubectl apply -f stack.yaml
kubectl -n schema-migrator get svc schema-migrator-edge -w
```

Backend startup fails closed when TiDB is older than v8.5, the session is not UTC, or the canonical migration ledger/readiness checksum is missing or mismatched. External migration target credentials are entered per target and encrypted in TiDB; no global PostgreSQL target credential is mounted.
