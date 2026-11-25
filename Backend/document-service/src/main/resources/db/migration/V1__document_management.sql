create table document (
 id uuid primary key, logical_name varchar(500) not null, document_type varchar(50) not null,
 classification varchar(30) not null check(classification in ('PUBLIC','INTERNAL','CONFIDENTIAL','RESTRICTED')),
 owner_user_id uuid not null, current_version_number integer not null check(current_version_number>0),
 status varchar(30) not null check(status in ('PROCESSING','AVAILABLE','QUARANTINED','REJECTED','DELETED')),
 context jsonb not null, access_version bigint not null default 1, version bigint not null default 1,
 deleted_at timestamptz, deleted_by uuid, deletion_reason text,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(), created_by uuid not null, updated_by uuid not null
);
create index document_owner on document(owner_user_id,status);
create index document_classification on document(classification,status);
create index document_context on document using gin(context);
create table document_version (
 id uuid primary key, document_id uuid not null references document(id), version_number integer not null check(version_number>0),
 original_file_name varchar(500) not null, declared_content_type varchar(150) not null, detected_content_type varchar(150),
 size_bytes bigint not null check(size_bytes>0), sha256 char(64) not null, storage_key text not null unique,
 processing_status varchar(30) not null check(processing_status in ('UPLOADED','SCANNING','EXTRACTING','READY','FAILED','INFECTED')),
 scan_status varchar(20) not null default 'PENDING', extraction_status varchar(20) not null default 'PENDING', indexing_status varchar(20) not null default 'PENDING',
 uploaded_at timestamptz not null default now(), uploaded_by uuid not null, unique(document_id,version_number)
);
create table upload_session (
 id uuid primary key, initiated_by uuid not null, target_document_id uuid references document(id),
 request_body jsonb not null, temporary_storage_key text not null unique,
 status varchar(30) not null check(status in ('INITIALIZED','COMPLETED','CANCELLED','EXPIRED')),
 expires_at timestamptz not null, completed_at timestamptz, created_at timestamptz not null default now(),
 document_id uuid references document(id), document_version integer
);
create index upload_expiry on upload_session(expires_at,status);
create table document_association (
 id uuid primary key, document_id uuid not null references document(id), resource_type varchar(30) not null,
 resource_id uuid not null, project_id uuid, association_type varchar(50) not null, context jsonb not null,
 created_at timestamptz not null default now(), created_by uuid not null,
 unique(document_id,resource_type,resource_id,association_type)
);
create index document_association_resource on document_association(resource_type,resource_id);
create table document_processing_job (
 id uuid primary key, document_version_id uuid not null references document_version(id), job_type varchar(30) not null,
 status varchar(20) not null default 'PENDING', attempt_count integer not null default 0,
 next_attempt_at timestamptz, started_at timestamptz, completed_at timestamptz, failure_code varchar(100),
 result_metadata jsonb, unique(document_version_id,job_type)
);
create table extracted_content (
 id uuid primary key, document_version_id uuid not null unique references document_version(id),
 content_storage_key text not null, content_sha256 char(64) not null, segment_count integer not null check(segment_count>=0),
 extracted_at timestamptz not null default now(), index_result jsonb
);
create table document_hold (
 id uuid primary key, document_id uuid not null references document(id), document_version integer not null,
 project_id uuid not null, purpose varchar(30) not null, caller varchar(100) not null,
 created_at timestamptz not null default now(), unique(document_id,id),
 foreign key(document_id,document_version) references document_version(document_id,version_number)
);
create table idempotency_record (
 id uuid primary key, scope text not null, key varchar(200) not null, request_hash char(64) not null,
 response_body jsonb not null, created_at timestamptz not null default now(), unique(scope,key)
);
create table outbox_event (
 id uuid primary key, aggregate_type varchar(100) not null, aggregate_id uuid not null, event_type varchar(150) not null,
 event_version integer not null, payload jsonb not null, occurred_at timestamptz not null,
 correlation_id varchar(100), actor_id varchar(255), published_at timestamptz,
 attempt_count integer not null default 0, next_attempt_at timestamptz
);
create index pending_outbox on outbox_event(occurred_at,id) where published_at is null;
create table processed_event (event_id uuid not null, consumer_name varchar(150) not null,event_type varchar(150) not null,processed_at timestamptz not null default now(),result varchar(20) not null,primary key(event_id,consumer_name));
