-- HTTP replay records, retained mutation history, immutable approval snapshots and project references.
CREATE TABLE proposal_api_record (
    id varchar(200) PRIMARY KEY,
    request_hash varchar(64) NOT NULL,
    payload text NOT NULL
);
-- Draft cancellation must not fabricate submission metadata.
ALTER TABLE proposal DROP CONSTRAINT ck_proposal_submission;
ALTER TABLE proposal ADD CONSTRAINT ck_proposal_submission
    CHECK (status IN ('DRAFT', 'CANCELLED') OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL));
