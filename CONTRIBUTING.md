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

Use the pull request template. If you add or change an observe server endpoint, update `nexora-cli/src/main/resources/openapi.yaml`. `OpenApiSpecTest` fails the build when the spec and the server disagree.
