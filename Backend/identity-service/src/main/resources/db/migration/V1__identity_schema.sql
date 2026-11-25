create table identity_policy (id integer primary key check (id=1), version bigint not null);
insert into identity_policy values (1,1);
create table app_user (
 id uuid primary key, identity_subject varchar(255) not null unique,
 institutional_id varchar(100) unique, email varchar(320) not null,
 display_name varchar(250) not null, given_name varchar(150), family_name varchar(150),
 status varchar(30) not null check(status in ('ACTIVE','DEACTIVATED','PENDING')),
 locale varchar(20), last_authenticated_at timestamptz,
 created_at timestamptz not null, created_by uuid not null,
 updated_at timestamptz not null, updated_by uuid not null, version bigint not null default 1
);
create unique index uq_app_user_email on app_user(lower(email));
create index ix_app_user_status on app_user(status);
create table app_role (
 id uuid primary key, code varchar(50) not null unique, name varchar(150) not null,
 description text, assignable boolean not null default true, active boolean not null default true,
 created_at timestamptz not null, created_by uuid not null,
 updated_at timestamptz not null, updated_by uuid not null, version bigint not null default 1
);
create table app_permission (
 id uuid primary key, code varchar(100) not null unique, description text not null,
 resource_type varchar(50) not null, action varchar(50) not null, active boolean not null default true
);
create table user_role (
 user_id uuid not null references app_user(id), role_id uuid not null references app_role(id),
 assigned_at timestamptz not null, assigned_by uuid not null, reason text,
 primary key(user_id,role_id)
);
create index ix_user_role_role on user_role(role_id,user_id);
create table role_permission (
 role_id uuid not null references app_role(id), permission_id uuid not null references app_permission(id),
 primary key(role_id,permission_id)
);
create table user_status_history (
 id uuid primary key, user_id uuid not null references app_user(id),
 previous_status varchar(30), new_status varchar(30) not null, reason text,
 changed_at timestamptz not null, changed_by uuid not null
);
create index ix_user_status_history_user on user_status_history(user_id,changed_at desc);
create table outbox_event (
 id uuid primary key, aggregate_type varchar(100) not null, aggregate_id uuid not null,
 event_type varchar(150) not null, event_version integer not null, payload jsonb not null,
 occurred_at timestamptz not null, correlation_id varchar(100), actor_id varchar(255),
 published_at timestamptz, attempt_count integer not null default 0, next_attempt_at timestamptz
);
create index ix_outbox_event_pending on outbox_event(occurred_at) where published_at is null;
create index ix_outbox_event_aggregate on outbox_event(aggregate_type,aggregate_id);
create table idempotency_record (
 caller_id varchar(255) not null, operation varchar(100) not null, idempotency_key varchar(200) not null,
 request_hash varchar(64) not null, response_body text not null, resource_id uuid not null,
 created_at timestamptz not null, expires_at timestamptz not null,
 primary key(caller_id,operation,idempotency_key)
);
