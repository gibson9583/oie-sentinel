# Oracle installation failure in Sentinel 1.1.0

[Issue #18](https://github.com/gibson9583/oie-sentinel/issues/18) reports
`ORA-00907: missing right parenthesis` while executing `/oracle-sentinel-tables.sql`
on Oracle 19c during startup.

Sentinel 1.1.0 defined several columns as `NOT NULL DEFAULT ...`. Oracle requires
the default expression before the inline constraint, for example:

```sql
severity VARCHAR2(16) DEFAULT 'WARNING' NOT NULL
```

The [Oracle 19c CREATE TABLE reference](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/CREATE-TABLE.html)
documents column definitions and includes examples with this ordering. The
correction shipped in [Sentinel 1.1.1](releases/1.1.1.md) and is also in 1.1.2.

## Recovery

Install Sentinel **1.1.1 or later** over the existing plugin and restart the
engine. Do not uninstall as an upgrade step. In a cluster, stop Sentinel on every
node and update every node before monitoring resumes.

The reported syntax error is in the first `CREATE TABLE`, so that statement does
not create `sentinel_monitor`. If no Sentinel tables exist, the updated plugin
can perform a fresh installation. Existing complete schemas upgrade through the
normal versioned migrations, preserving their data.

If startup instead reports `Sentinel schema is partially applied`, preserve the
database and have a DBA inspect the tables against the packaged migrations.
Oracle DDL auto-commits; tables created by earlier successful statements can
remain after a later failure. The migrator deliberately rejects an incomplete
initial schema and does not automatically delete or repair it. Capture the full
exception and table inventory before attempting a manual repair.

## Regression validation

The ordinary server tests check every packaged Oracle SQL migration using the
engine's script reader and reject `NOT NULL DEFAULT` ordering. The Docker-backed
Oracle matrix also rejects the old ordering (the 19c issue reports `ORA-00907`;
Free 23 reports `ORA-03076`), installs the packaged schema from empty, verifies
generated IDs and omitted monitor defaults,
and verifies an explicit null update fails without changing the row. Existing
matrix cases cover upgrades from every schema version, repeated startup,
interrupted migrations, mapper statements, and uninstall/reinstall.

```bash
mvn -pl server -am test
mvn -pl server -am verify -Pdb-matrix -Dsentinel.db.vendor=oracle
```

The matrix uses Oracle Free 23; running it is not a claim of direct Oracle 19c
runtime validation. Logs, database observations, image provenance, and container
cleanup receipts are written under `server/target/db-matrix-evidence/oracle/`.
