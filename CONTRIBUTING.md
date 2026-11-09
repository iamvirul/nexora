# Contributing to Nexora

## Building

```bash
mvn test              # unit tests only
mvn verify            # unit and integration tests, plus the formatting check
mvn verify -DskipITs  # everything except integration tests (no Docker needed)
mvn verify -DskipUTs  # integration tests only
mvn spotless:apply    # fix formatting (palantir-java-format)
```

`mvn verify` fails on unformatted code, and CI runs the same check.

### Integration tests

Integration tests run against real dependencies through Testcontainers, so they need a running Docker daemon. Without one they fail instead of skipping; pass `-DskipITs` to leave them out.

Name the class `*IT` and tag it `@Tag("integration")`. Surefire excludes the tag, so `mvn test` stays fast, and failsafe runs it during `mvn verify`. CI runs them as a separate step with a 3-minute limit. `JdbcExecutionStorePostgresIT` is the reference: it runs the store against PostgreSQL 15, the oldest version Nexora supports.

## Database migrations

`JdbcExecutionStore` runs Flyway when it is constructed and applies any pending migration before the first query. If a migration fails, the store fails to construct, so Nexora does not start with a half-migrated schema.

### Where they live

```
nexora-persistence/src/main/resources/db/nexora/migration/
```

History is recorded in the `nexora_schema_history` table. The location and the table both have their own names so that an application running its own Flyway against the same database, which by default uses `db/migration` and `flyway_schema_history`, never picks up Nexora's migrations or shares their history.

### Naming

```
V<version>__<description>.sql
```

- `<version>` is the next whole number after the highest existing one: `V2`, `V3`, and so on.
- Two underscores separate the version from the description.
- `<description>` is lowercase snake_case and says what the migration does, for example `V2__add_execution_tenant_id.sql`.

### Rules

1. **Never edit, rename, or delete a migration once it is merged.** Flyway stores a checksum for every applied script and refuses to start when one changes. To fix a mistake, add a new migration.
2. **Roll back by migrating forward.** Flyway Community has no undo, so reverting a change means writing a new migration that undoes it.
3. **Write portable SQL.** Every migration must run on H2 (the embedded default) and on PostgreSQL 15 or later. Use standard `MERGE ... USING` for upserts. Do not use H2's `MERGE ... KEY` or PostgreSQL's `ON CONFLICT`, since neither runs on the other database.
4. **Keep the previous release working.** During a rolling deploy, instances on the previous release keep running against the new schema. Add columns as nullable or with a default, and drop a column only in a later release, after no released version reads it.
5. **Only `V1` uses `IF NOT EXISTS`.** It is there so that stores created before Flyway are adopted cleanly. Later migrations use plain DDL so that their history is exact.
6. **Add a test.** At minimum, check that the store constructs on a fresh database and on one migrated to the previous version.

### Readiness

`GET /health/ready` reports `migrations: DOWN` when a migration is recorded as failed, or when the database schema is older than the one this release migrated to, for example after restoring an old backup.

## Pull requests

Use the pull request template. If you add or change an observe server endpoint, update `nexora-cli/src/main/resources/openapi.yaml`. `OpenApiSpecTest` fails the build when the spec and the server disagree. The spec's `info.version` is the API's own version, separate from the Nexora version: when your change is the first API change since the last release, bump it by the rules in `website/docs/http-api.md` and add the change to the API changelog there. The docs site publishes the spec to GitHub Pages from `main`.

## Kubernetes chart

The chart lives in `deploy/helm/nexora`. After changing it:

- run `deploy/k8s/generate.sh` and commit `deploy/k8s/nexora.yaml`; the `Helm chart` workflow fails when they differ,
- bump `version` in `Chart.yaml`; `appVersion` follows the Nexora release and changes with it,
- add a value set under `deploy/helm/nexora/ci/` for any new option, so CI lints, dry-runs and installs it.

Check locally with `helm lint --strict deploy/helm/nexora` and `helm template nexora deploy/helm/nexora -f <values>`.

## Releases

A `v*` tag publishes the library modules to Maven Central (`Publish` workflow) and the container image (`Container image` workflow): `ghcr.io/iamvirul/nexora:<version>`, `:<major>.<minor>` and `:latest`. Instead of pushing the tag, you can run `Publish` from the Actions tab on `main` with the new tag name: it builds `main`, creates the tag and the GitHub Release only after Central accepts the upload, and starts the image build. Run with an existing tag, it publishes that tag again, for example after a failed upload. GHCR creates a new package as private, so after the first image is published, set the `nexora` package to public in the repository's GitHub Packages settings; until then, clusters cannot pull the chart's default image.

### Maven Central

The library modules are published under `me.virul.nexora` through the Central Publisher Portal. The `release` profile in the root `pom.xml` adds sources and javadoc JARs, signs everything and uploads it as one bundle; Central publishes it only once every module passes validation. `nexora-cli`, `nexora-planner-llm`, `nexora-capabilities` (no code yet) and the demos under `tests/` are excluded there. `nexora-all` is the BOM: when a module is published or dropped, update both its list and the profile's `excludeArtifacts`.

One-time setup, done by the namespace owner:

1. Sign in at [central.sonatype.com](https://central.sonatype.com) and add the namespace `me.virul`. Verify it with the DNS TXT record it shows, set on `virul.me` itself: `nexora.virul.me` is a CNAME to GitHub Pages and cannot also hold a TXT record. `me.virul` covers `me.virul.nexora`.
2. Generate a user token (Account > Generate User Token).
3. Create a signing key, for example `gpg --quick-gen-key "Nexora Releases <you@example.com>" rsa4096 sign 2y`, and publish its public key to `keyserver.ubuntu.com` and `keys.openpgp.org` (`gpg --keyserver <server> --send-keys <fingerprint>`). Central checks signatures against public keyservers.
4. Add four repository secrets: `CENTRAL_USERNAME` and `CENTRAL_TOKEN` from the token, `GPG_PRIVATE_KEY` from `gpg --armor --export-secret-keys <key id>`, and `GPG_PASSPHRASE`.

A version can be published to Central only once and cannot be removed. To check a release before tagging, build the bundle locally without uploading it:

```bash
MAVEN_GPG_KEY="$(gpg --armor --export-secret-keys <key id>)" MAVEN_GPG_PASSPHRASE=... \
  mvn -P release deploy -DskipTests -DskipUpload=true
```

This needs a `central` server entry in `~/.m2/settings.xml` (any credentials do when not uploading), and a release version: the bundle in `target/central-publishing/central-bundle.zip` is built only for non-SNAPSHOT versions.
