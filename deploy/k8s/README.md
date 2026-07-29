# Schema Migrator standalone Kubernetes stack

These manifests deploy the Schema Migrator backend, UI and edge router. They
do not deploy TiDB, PostgreSQL, MongoDB or Keycloak.

The backend's sole internal state store is an externally provisioned TiDB 8.5+
`schema_migrator` database. PostgreSQL may be configured later as an external
migration target; it is not mounted as application state. Oracle compatibility
is deprecated.

## Prerequisites

1. Provision the `schema_migrator` TiDB database and a dedicated non-root
   application account.
2. Apply the parent repository's canonical `sql/tidb/schema_migrator` manifest
   with the provisioning schema executor and its separate DDL credential.
3. Create a PKCS12 truststore containing the TiDB server CA.
4. Populate `secret.template.yaml` with the truststore and required
   `BEDROCK_STATE_DB_*`, auth and encryption values.
5. Set a JDBC URL ending in `/schema_migrator` with
   `sslMode=VERIFY_IDENTITY`.
6. Configure the external OIDC issuer/JWKS and replace the pinned image and
   public-origin placeholders in `stack.yaml`.

## Apply

```bash
kubectl apply -f namespace.yaml
kubectl apply -f secret.template.yaml
kubectl apply -f stack.yaml
kubectl -n schema-migrator get svc schema-migrator-edge -w
```

Startup fails closed for TiDB older than 8.5, a non-UTC session, a wrong
database, invalid TLS identity, or missing/mismatched canonical migration
readiness. External target credentials are entered per target and encrypted in
TiDB; no global PostgreSQL target credential is mounted.

The parent Helm chart has a different topology and deploys Keycloak with its
own separate database. See the parent
[`docs/architecture.md`](../../../../docs/architecture.md).
