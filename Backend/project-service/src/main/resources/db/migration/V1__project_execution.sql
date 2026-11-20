create table project (
 id uuid primary key, project_number varchar(40) not null unique,
 source_proposal_id uuid not null unique, approval_id uuid not null, proposal_version bigint not null check(proposal_version>0),
 approved_snapshot jsonb not null, title varchar(500) not null, abstract_text text,
 research_area_code varchar(50) not null, status varchar(30) not null check(status in ('APPROVED','ACTIVE','DELAYED','COMPLETED','CLOSED','CANCELLED')),
 classification varchar(30) not null default 'INTERNAL' check(classification in ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
 access_version bigint not null default 1, principal_researcher_id uuid not null, coordinator_user_id uuid not null,
 start_date date, planned_end_date date not null, actual_completion_date date, closure_date date, closed_by uuid,
 progress_percent numeric(5,2) not null default 0 check(progress_percent between 0 and 100),
 progress_method varchar(30) not null default 'ACTIVITY_WEIGHT' check(progress_method in ('ACTIVITY_WEIGHT','MILESTONE_WEIGHT')),
 created_at timestamptz not null, created_by uuid not null, updated_at timestamptz not null, updated_by uuid not null,
 version bigint not null default 1 check(version>0), check(start_date is null or planned_end_date>=start_date)
);
create index ix_project_status on project(status,planned_end_date);
create index ix_project_principal on project(principal_researcher_id,status);
create index ix_project_coordinator on project(coordinator_user_id,status);
create table project_member (
 project_id uuid not null references project(id), user_id uuid not null, project_role varchar(50) not null
 check(project_role in ('PRINCIPAL_RESEARCHER','RESEARCHER','STUDENT','COLLABORATOR')),
 status varchar(20) not null check(status in ('ACTIVE','REMOVED')),
 joined_at timestamptz not null, added_by uuid not null, removed_at timestamptz, removed_by uuid,
 version bigint not null default 1, primary key(project_id,user_id)
);
create index ix_member_user on project_member(user_id,status);
create table project_member_permission (
 project_id uuid not null, user_id uuid not null, permission_code varchar(100) not null,
 granted_at timestamptz not null, granted_by uuid not null,
 primary key(project_id,user_id,permission_code), foreign key(project_id,user_id) references project_member(project_id,user_id)
);
create table project_status_history (
 id uuid primary key, project_id uuid not null references project(id), from_status varchar(30), to_status varchar(30) not null,
 reason text, changed_at timestamptz not null, changed_by uuid not null, correlation_id varchar(100) not null
);
create index ix_history_project on project_status_history(project_id,changed_at desc);
create table milestone (
 id uuid primary key, project_id uuid not null references project(id), name varchar(250) not null, description text,
 deadline date not null, status varchar(30) not null check(status in ('PLANNED','IN_PROGRESS','COMPLETED','DELAYED','CANCELLED')),
 weight numeric(6,3) not null default 1 check(weight>0), completed_at timestamptz, display_order integer not null default 0 check(display_order>=0),
 created_at timestamptz not null, created_by uuid not null, updated_at timestamptz not null, updated_by uuid not null, version bigint not null default 1,
 unique(project_id,id)
);
create index ix_milestone_project on milestone(project_id,status);
create index ix_milestone_deadline on milestone(deadline,status);
create table activity (
 id uuid primary key, project_id uuid not null references project(id), milestone_id uuid, name varchar(250) not null, description text,
 responsible_user_id uuid not null, start_date date not null, deadline date not null,
 status varchar(30) not null check(status in ('NOT_STARTED','IN_PROGRESS','BLOCKED','COMPLETED','CANCELLED')),
 weight numeric(6,3) not null default 1 check(weight>0), completed_at timestamptz, block_reason text,
 created_at timestamptz not null, created_by uuid not null, updated_at timestamptz not null, updated_by uuid not null, version bigint not null default 1,
 check(deadline>=start_date), unique(project_id,id), foreign key(project_id,milestone_id) references milestone(project_id,id)
);
create index ix_activity_project on activity(project_id,status);
create index ix_activity_milestone on activity(milestone_id,status);
create index ix_activity_assignee on activity(responsible_user_id,status);
create index ix_activity_deadline on activity(deadline,status);
create table evidence_link (
 id uuid primary key, project_id uuid not null references project(id), activity_id uuid, milestone_id uuid,
 document_id uuid not null, document_version integer not null check(document_version>0), sha256 varchar(64) not null,
 description text, linked_at timestamptz not null, linked_by uuid not null,
 check((activity_id is not null)::integer+(milestone_id is not null)::integer=1),
 foreign key(project_id,activity_id) references activity(project_id,id), foreign key(project_id,milestone_id) references milestone(project_id,id)
);
create unique index uq_activity_evidence on evidence_link(activity_id,document_id) where activity_id is not null;
create unique index uq_milestone_evidence on evidence_link(milestone_id,document_id) where milestone_id is not null;
create table project_progress_snapshot (
 id uuid primary key, project_id uuid not null references project(id), progress_percent numeric(5,2) not null,
 calculation_method varchar(30) not null, calculation_details jsonb not null, calculated_at timestamptz not null, trigger_event_id uuid
);
create index ix_progress_project on project_progress_snapshot(project_id,calculated_at desc);
create table final_report (
 id uuid primary key, project_id uuid not null unique references project(id), document_id uuid not null,
 document_version integer not null check(document_version>0), sha256 varchar(64) not null,
 status varchar(20) not null check(status in ('DRAFT','SUBMITTED','REOPENED')),
 submitted_at timestamptz, submitted_by uuid, reopened_at timestamptz, reopened_by uuid, reopening_reason text, hold_id uuid,
 created_at timestamptz not null, created_by uuid not null, updated_at timestamptz not null, updated_by uuid not null, version bigint not null default 1
);
create table publication (
 id uuid primary key, project_id uuid not null references project(id), title varchar(500) not null, publication_type varchar(50) not null,
 doi varchar(255), publication_date date, url text, journal_or_venue varchar(300), document_id uuid,
 created_at timestamptz not null, created_by uuid not null, updated_at timestamptz not null, updated_by uuid not null, version bigint not null default 1
);
create unique index uq_publication_doi on publication(lower(doi)) where doi is not null;
create index ix_publication_project on publication(project_id,publication_date desc);
create table publication_author (
 id uuid primary key, publication_id uuid not null references publication(id) on delete cascade,
 user_id uuid, display_name varchar(250) not null, orcid varchar(50), display_order integer not null,
 unique(publication_id,display_order)
);
create table project_closure_check (
 id uuid primary key, project_id uuid not null references project(id), condition_code varchar(100) not null,
 satisfied boolean not null, details text, checked_at timestamptz not null, checked_by uuid,
 unique(project_id,condition_code)
);
create table outbox_event (
 id uuid primary key, aggregate_type varchar(100) not null, aggregate_id uuid not null, event_type varchar(150) not null,
 event_version integer not null, payload jsonb not null, occurred_at timestamptz not null, correlation_id varchar(100) not null,
 actor_id varchar(255), published_at timestamptz, attempt_count integer not null default 0, next_attempt_at timestamptz
);
create index ix_outbox_pending on outbox_event(occurred_at) where published_at is null;
create table processed_event (
 event_id uuid not null, consumer_name varchar(150) not null, event_type varchar(150) not null,
 processed_at timestamptz not null, result varchar(20) not null, payload_hash varchar(64) not null,
 primary key(event_id,consumer_name)
);
create table idempotency_record (
 caller_id varchar(255) not null, operation varchar(200) not null, idempotency_key varchar(200) not null,
 request_hash varchar(64) not null, response_body jsonb not null, created_at timestamptz not null, expires_at timestamptz not null,
 primary key(caller_id,operation,idempotency_key)
);
create table document_hold_intent (
 id uuid primary key, project_id uuid not null, document_id uuid not null, document_version integer not null,
 status varchar(30) not null check(status in ('INTENT','HELD','CONFIRMED','RELEASE_PENDING','RELEASED')),
 created_at timestamptz not null, updated_at timestamptz not null
);
create table aggregate_tombstone (
 aggregate_type varchar(30) not null, aggregate_id uuid not null, project_id uuid not null, version bigint not null,
 deleted_at timestamptz not null, primary key(aggregate_type,aggregate_id)
);
create table deadline_notice (
 resource_type varchar(30) not null, resource_id uuid not null, deadline date not null,
 detected_at timestamptz not null, primary key(resource_type,resource_id,deadline)
);
create table proposal_reference_job (
 proposal_id uuid primary key, approval_id uuid not null, project_id uuid not null,
 completed_at timestamptz, attempt_count integer not null default 0, next_attempt_at timestamptz
);
create table projection_export (id uuid primary key, projection varchar(30) not null, created_at timestamptz not null, expires_at timestamptz not null);
create table projection_export_item (
 snapshot_id uuid not null references projection_export(id) on delete cascade, ordinal bigint not null,
 aggregate_type varchar(30) not null, aggregate_id uuid not null, aggregate_version bigint not null, state jsonb not null,
 primary key(snapshot_id,ordinal)
);
create table incoming_event (
 id uuid primary key, event_type varchar(150) not null, payload jsonb not null, payload_hash varchar(64) not null,
 status varchar(20) not null check(status in ('PENDING','SUCCEEDED','FAILED')), attempt_count integer not null default 0,
 next_attempt_at timestamptz, received_at timestamptz not null, last_error_code varchar(100)
);
create table document_revocation (document_id uuid primary key, access_version bigint not null, deleted_at timestamptz not null);
