alter table proposal_reference_job add column status varchar(20) not null default 'PENDING'
 check(status in ('PENDING','CONFLICT','COMPLETED'));
alter table proposal_reference_job add column last_error_code varchar(100);
