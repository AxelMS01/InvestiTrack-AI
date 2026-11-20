update proposal set version=1 where version=0;
alter table proposal add column access_version bigint not null default 1 check(access_version>0);
alter table proposal_api_record add column created_at timestamptz not null default now();
alter table proposal_api_record add column expires_at timestamptz;
update proposal_api_record set expires_at=created_at+interval '24 hours' where id like 'request:%';
create table proposal_incoming_event (
 id uuid primary key, event_type varchar(150) not null, payload jsonb not null, payload_hash varchar(64) not null,
 status varchar(20) not null check(status in ('PENDING','SUCCEEDED','FAILED')), attempt_count integer not null default 0,
 next_attempt_at timestamptz, received_at timestamptz not null, last_error_code varchar(100)
);
create table proposal_projection_export (id uuid primary key, projection varchar(30) not null, created_at timestamptz not null, expires_at timestamptz not null);
create table proposal_projection_export_item (
 snapshot_id uuid not null references proposal_projection_export(id) on delete cascade, ordinal bigint not null,
 aggregate_type varchar(30) not null, aggregate_id uuid not null, aggregate_version bigint not null, state jsonb not null,
 primary key(snapshot_id,ordinal)
);
create table proposal_tombstone (id uuid primary key, version bigint not null, deleted_at timestamptz not null);
create table proposal_document_revocation (document_id uuid primary key, access_version bigint not null, deleted_at timestamptz not null);
