CREATE TABLE proposal
(
    id                        UUID                     NOT NULL,
    proposal_number           VARCHAR(40),
    title                     VARCHAR(500)             NOT NULL,
    abstract_text             TEXT,
    research_problem          TEXT,
    methodology               TEXT,
    expected_results          TEXT,
    research_area_id          UUID,
    estimated_duration_months INTEGER,
    status                    VARCHAR(30)              NOT NULL,
    owner_user_id             UUID                     NOT NULL,
    submitted_at              TIMESTAMP WITH TIME ZONE,
    submitted_by              UUID,
    cancelled_at              TIMESTAMP WITH TIME ZONE,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                UUID                     NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by                UUID                     NOT NULL,
    version                   BIGINT                   NOT NULL,
    CONSTRAINT pk_proposal PRIMARY KEY (id)
);

ALTER TABLE proposal
    ADD CONSTRAINT uq_proposal_proposal_number UNIQUE (proposal_number);

CREATE INDEX ix_proposal_owner_status ON proposal (owner_user_id, status);

CREATE INDEX ix_proposal_research_area_status ON proposal (research_area_id, status);

CREATE INDEX ix_proposal_status_submitted_at ON proposal (status, submitted_at);