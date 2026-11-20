alter table outbox_event add column quarantined_at timestamptz;
alter table outbox_event add column last_error_code varchar(100);
-- Old API envelopes carried full proposal/evaluation bodies and lacked aggregate metadata.
-- Preserve them for review, but never start publishing that content when messaging is enabled.
update outbox_event set quarantined_at=now(),last_error_code='LEGACY_EVENT_REVIEW_REQUIRED'
 where published_at is null and (jsonb_typeof(payload->'aggregate') is distinct from 'object'
 or (payload->'data') ?| array['abstract','researchProblem','methodology','observations','recommendations','criteria']);
