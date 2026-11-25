create sequence audit_sequence;
create table audit_event (
 id uuid primary key, sequence_number bigint not null unique,
 occurred_at timestamptz not null, received_at timestamptz not null,
 actor_type varchar(20) not null check(actor_type in ('USER','SERVICE','SYSTEM')), actor_id varchar(255) not null,
 action varchar(100) not null, resource_type varchar(50) not null, resource_id uuid, project_id uuid,
 outcome varchar(20) not null check(outcome in ('SUCCESS','FAILURE','DENIED')),
 correlation_id varchar(100) not null, source_service varchar(100) not null,
 metadata jsonb not null, previous_hash char(64), event_hash char(64) not null,
 payload_hash char(64) not null
);
create index audit_chronology on audit_event(occurred_at desc,id desc);
create index audit_actor on audit_event(actor_id,occurred_at desc,id desc);
create index audit_resource on audit_event(resource_type,resource_id,occurred_at desc,id desc);
create index audit_project on audit_event(project_id,occurred_at desc,id desc);
create index audit_correlation on audit_event(correlation_id);
create table processed_event (
 event_id uuid not null references audit_event(id), consumer_name varchar(150) not null,
 event_type varchar(150) not null, processed_at timestamptz not null default now(), result varchar(20) not null,
 primary key(event_id,consumer_name)
);
create function reject_audit_mutation() returns trigger language plpgsql as $$
begin
 raise exception 'Audit history is append-only' using errcode='42501';
end;
$$;
create trigger immutable_audit_rows before update or delete on audit_event for each row execute function reject_audit_mutation();
create trigger immutable_audit_truncate before truncate on audit_event for each statement execute function reject_audit_mutation();
create trigger immutable_inbox_rows before update or delete on processed_event for each row execute function reject_audit_mutation();
create trigger immutable_inbox_truncate before truncate on processed_event for each statement execute function reject_audit_mutation();
create table audit_export_job (
 id uuid primary key, requested_by uuid not null, filter_definition jsonb not null,
 upper_sequence bigint not null, status varchar(20) not null check(status in ('QUEUED','RUNNING','COMPLETED','FAILED','EXPIRED')),
 format varchar(20) not null default 'CSV', artifact bytea, record_count integer,
 requested_at timestamptz not null default now(), started_at timestamptz, completed_at timestamptz, expires_at timestamptz,
 failure_code varchar(100)
);
create index audit_export_pending on audit_export_job(status,requested_at);
create table download_grant (
 id uuid primary key, job_id uuid not null references audit_export_job(id), requested_by uuid not null,
 token_hash char(64) not null unique, expires_at timestamptz not null
);
create table idempotency_record (
 id uuid primary key, caller_id uuid not null, key varchar(200) not null, request_hash char(64) not null,
 response_body jsonb not null, created_at timestamptz not null default now(), unique(caller_id,key)
);
