-- Run AFTER Flyway, as the migration owner, against this service's dedicated database:
-- psql -v ON_ERROR_STOP=1 -v runtime_role=audit_runtime -f deploy/runtime-grants.sql
-- Provision the LOGIN role and its password separately; it must not own this database,
-- schema, tables, or functions and must not inherit a privileged/owner role.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM :"runtime_role";
GRANT USAGE ON SCHEMA public TO :"runtime_role";
REVOKE ALL ON audit_event, processed_event FROM :"runtime_role";
REVOKE ALL ON audit_event, processed_event FROM PUBLIC;
GRANT SELECT, INSERT ON audit_event, processed_event TO :"runtime_role";
GRANT USAGE, SELECT ON SEQUENCE audit_sequence TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON audit_export_job, download_grant, idempotency_record TO :"runtime_role";
REVOKE ALL ON FUNCTION reject_audit_mutation() FROM PUBLIC;
