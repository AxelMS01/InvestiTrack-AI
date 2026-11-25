-- Owned reporting data only. Tombstones retain source versions to prevent resurrection.
create table projection_state (
 aggregate_type varchar(30) not null, aggregate_id uuid not null, source_version bigint not null check(source_version>0),
 state jsonb not null, deleted boolean not null default false, received_at timestamptz not null default clock_timestamp(),
 primary key(aggregate_type,aggregate_id)
);
create index projection_project on projection_state ((state->>'projectId'));
create index projection_evaluator on projection_state ((state->>'evaluatorUserId'));
create index projection_deadline on projection_state ((state->>'deadline'));
create table incoming_event (
 id uuid primary key, payload jsonb not null, status varchar(20) not null default 'PENDING',
 received_at timestamptz not null default clock_timestamp(), attempt_count integer not null default 0,
 next_attempt_at timestamptz, last_error_code varchar(100)
);
create index incoming_pending on incoming_event(status,next_attempt_at);
create table processed_event (event_id uuid primary key references incoming_event(id), processed_at timestamptz not null default now());
create table report_definition (
 code varchar(100) primary key, name varchar(250) not null, description text not null,
 allowed_roles jsonb not null, supported_formats jsonb not null, parameter_schema jsonb not null,
 active boolean not null default true, version integer not null default 1
);
insert into report_definition(code,name,description,allowed_roles,supported_formats,parameter_schema) values
 ('PROJECT_PORTFOLIO_STATUS','Project portfolio status','Current authorized project status and progress.',
 '["ADMIN","COORDINATOR","RESEARCHER","STUDENT"]','["CSV"]',
 '{"type":"object","additionalProperties":false,"properties":{"researchArea":{"type":"string"},"status":{"type":"array","items":{"type":"string"}},"projectIds":{"type":"array","maxItems":100,"items":{"type":"string","format":"uuid"}},"coordinatorId":{"type":"string","format":"uuid"}}}'),
 ('PROJECT_DEADLINES','Project deadlines','Current authorized activities and milestones.',
 '["ADMIN","COORDINATOR","RESEARCHER","STUDENT"]','["CSV"]',
 '{"type":"object","additionalProperties":false,"properties":{"projectIds":{"type":"array","maxItems":100,"items":{"type":"string","format":"uuid"}},"state":{"enum":["UPCOMING","OVERDUE","FUTURE","COMPLETED"]},"type":{"enum":["ACTIVITY","MILESTONE"]},"from":{"type":"string","format":"date"},"to":{"type":"string","format":"date"}}}');
create table report_job (
 id uuid primary key, report_code varchar(100) not null references report_definition(code), requested_by uuid not null,
 parameters jsonb not null, format varchar(20) not null, status varchar(20) not null,
 resource_ids jsonb not null default '[]', artifact bytea, as_of timestamptz,
 requested_at timestamptz not null default now(), started_at timestamptz, completed_at timestamptz,
 expires_at timestamptz, failure_code varchar(100), failure_detail text,
 check(status in ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED','EXPIRED'))
);
create index report_owner on report_job(requested_by,requested_at desc);
create index report_pending on report_job(status,requested_at);
create table download_grant (
 id uuid primary key, job_id uuid not null references report_job(id), requested_by uuid not null,
 token_hash varchar(64) not null unique, expires_at timestamptz not null
);
create table rebuild_job (
 id uuid primary key, projection varchar(20) not null, scope jsonb not null, reason varchar(500) not null,
 status varchar(20) not null default 'QUEUED', processed_count bigint not null default 0,
 requested_at timestamptz not null default now(), started_at timestamptz, completed_at timestamptz, error_code varchar(100)
);
create table rebuild_stage (
 job_id uuid not null references rebuild_job(id) on delete cascade,
 aggregate_type varchar(30) not null, aggregate_id uuid not null, source_version bigint not null,
 state jsonb not null, deleted boolean not null default false,
 primary key(job_id,aggregate_type,aggregate_id)
);
create table idempotency_record (
 id uuid primary key, caller_id uuid not null, operation varchar(50) not null, key varchar(200) not null,
 payload jsonb not null, response_body jsonb not null, expires_at timestamptz not null,
 unique(caller_id,operation,key)
);
-- Read models are views of a common versioned store; they swap atomically during rebuilds.
create view project_summary_projection as select aggregate_id as project_id,state,source_version,received_at
 from projection_state where aggregate_type='PROJECT' and not deleted;
create view deadline_projection as select aggregate_type as resource_type,aggregate_id as resource_id,state,source_version,received_at
 from projection_state where aggregate_type in ('ACTIVITY','MILESTONE') and not deleted;
create view evaluation_summary_projection as select aggregate_id as assignment_id,state,source_version,received_at
 from projection_state where aggregate_type='EVALUATOR_ASSIGNMENT' and not deleted;
