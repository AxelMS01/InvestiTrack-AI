-- Complete the Proposal & Evaluation schema without changing existing migrations.

ALTER TABLE proposal ADD CONSTRAINT fk_proposal_research_area FOREIGN KEY (research_area_id) REFERENCES research_area (id);

ALTER TABLE proposal ADD CONSTRAINT ck_proposal_duration CHECK (estimated_duration_months > 0);

ALTER TABLE proposal ADD CONSTRAINT ck_proposal_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'));

ALTER TABLE proposal ADD CONSTRAINT ck_proposal_submission CHECK (status = 'DRAFT' OR (submitted_at IS NOT NULL AND submitted_by IS NOT NULL));

CREATE TABLE proposal_objective (
    id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL,
    objective_type varchar(20) NOT NULL,
    description text NOT NULL,
    display_order integer NOT NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_proposal_objective_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id),
    CONSTRAINT ck_proposal_objective_objective_type CHECK (objective_type IN ('GENERAL', 'SPECIFIC')),
    CONSTRAINT uq_proposal_objective_proposal_id_display_order UNIQUE (proposal_id, display_order),
    CONSTRAINT ck_proposal_objective_values CHECK (display_order >= 0)
);

CREATE TABLE proposal_document_reference (
    id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL,
    document_id uuid NOT NULL,
    purpose varchar(50) NOT NULL,
    linked_at timestamptz NOT NULL,
    linked_by uuid NOT NULL,
    CONSTRAINT fk_proposal_document_reference_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id),
    CONSTRAINT uq_proposal_document_reference_proposal_id_document_id_purpose UNIQUE (proposal_id, document_id, purpose)
);

CREATE TABLE proposal_status_history (
    id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL,
    from_status varchar(30),
    to_status varchar(30) NOT NULL,
    reason text,
    changed_at timestamptz NOT NULL,
    changed_by uuid NOT NULL,
    correlation_id varchar(100),
    CONSTRAINT fk_proposal_status_history_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id),
    CONSTRAINT ck_proposal_status_history_from_status CHECK (from_status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_proposal_status_history_to_status CHECK (to_status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX ix_proposal_status_history_proposal_id_changed_at ON proposal_status_history (proposal_id, changed_at desc);

CREATE TABLE evaluator_assignment (
    id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL,
    evaluator_user_id uuid NOT NULL,
    status varchar(30) NOT NULL,
    assigned_at timestamptz NOT NULL,
    assigned_by uuid NOT NULL,
    due_date date,
    instructions text,
    completed_at timestamptz,
    revoked_at timestamptz,
    revoked_by uuid,
    revocation_reason text,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_evaluator_assignment_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id),
    CONSTRAINT ck_evaluator_assignment_status CHECK (status IN ('ASSIGNED', 'ACCEPTED', 'DECLINED', 'COMPLETED', 'REVOKED'))
);

CREATE TABLE evaluation_criterion (
    id uuid PRIMARY KEY,
    code varchar(50),
    name varchar(200) NOT NULL,
    description text NOT NULL,
    minimum_score numeric(6,2) NOT NULL,
    maximum_score numeric(6,2) NOT NULL,
    weight numeric(6,3) NOT NULL,
    active_from date NOT NULL,
    active_until date,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_evaluation_criterion_code UNIQUE (code),
    CONSTRAINT ck_evaluation_criterion_values CHECK (weight > 0)
);

CREATE TABLE evaluation (
    id uuid PRIMARY KEY,
    assignment_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    evaluator_user_id uuid NOT NULL,
    status varchar(20) NOT NULL,
    observations text,
    recommendations text,
    decision varchar(30),
    total_score numeric(8,2),
    submitted_at timestamptz,
    released_at timestamptz,
    released_by uuid,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_evaluation_assignment FOREIGN KEY (assignment_id) REFERENCES evaluator_assignment (id),
    CONSTRAINT fk_evaluation_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id),
    CONSTRAINT ck_evaluation_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'RELEASED')),
    CONSTRAINT ck_evaluation_decision CHECK (decision IN ('APPROVE', 'REQUEST_CHANGES', 'REJECT')),
    CONSTRAINT uq_evaluation_assignment_id UNIQUE (assignment_id)
);

CREATE INDEX ix_evaluation_proposal_id_status ON evaluation (proposal_id, status);

CREATE INDEX ix_evaluation_evaluator_user_id_status ON evaluation (evaluator_user_id, status);

CREATE TABLE evaluation_score (
    id uuid PRIMARY KEY,
    evaluation_id uuid NOT NULL,
    criterion_id uuid NOT NULL,
    criterion_code_snapshot varchar(50) NOT NULL,
    criterion_name_snapshot varchar(200) NOT NULL,
    weight_snapshot numeric(6,3) NOT NULL,
    score numeric(6,2) NOT NULL,
    comment text,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT fk_evaluation_score_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluation (id),
    CONSTRAINT fk_evaluation_score_criterion FOREIGN KEY (criterion_id) REFERENCES evaluation_criterion (id),
    CONSTRAINT uq_evaluation_score_evaluation_id_criterion_id UNIQUE (evaluation_id, criterion_id)
);

CREATE TABLE proposal_decision (
    id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL,
    decision varchar(30) NOT NULL,
    rationale text,
    decided_at timestamptz NOT NULL,
    decided_by uuid NOT NULL,
    supersedes_decision_id uuid,
    CONSTRAINT fk_proposal_decision_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id),
    CONSTRAINT ck_proposal_decision_decision CHECK (decision IN ('APPROVED', 'CHANGES_REQUESTED', 'REJECTED')),
    CONSTRAINT fk_proposal_decision_supersedes_decision FOREIGN KEY (supersedes_decision_id) REFERENCES proposal_decision (id)
);

CREATE INDEX ix_proposal_decision_proposal_id_decided_at ON proposal_decision (proposal_id, decided_at desc);

CREATE TABLE outbox_event (
    id uuid PRIMARY KEY,
    aggregate_type varchar(100) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(150) NOT NULL,
    event_version integer NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    correlation_id varchar(100),
    actor_id varchar(255),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz
);

CREATE INDEX ix_outbox_event_aggregate_type_aggregate_id ON outbox_event (aggregate_type, aggregate_id);

CREATE TABLE proposal_researcher (
    proposal_id uuid NOT NULL,
    user_id uuid NOT NULL,
    proposal_role varchar(50) NOT NULL,
    responsibility text,
    added_at timestamptz NOT NULL,
    added_by uuid NOT NULL,
    CONSTRAINT pk_proposal_researcher PRIMARY KEY (proposal_id, user_id),
    CONSTRAINT fk_proposal_researcher_proposal FOREIGN KEY (proposal_id) REFERENCES proposal (id)
);

CREATE TABLE processed_event (
    event_id uuid PRIMARY KEY,
    consumer_name varchar(150) NOT NULL,
    event_type varchar(150) NOT NULL,
    processed_at timestamptz NOT NULL,
    result varchar(20) NOT NULL,
    CONSTRAINT ck_processed_event_result CHECK (result IN ('SUCCEEDED', 'IGNORED'))
);

-- Active assignments are those still awaiting completion.
CREATE UNIQUE INDEX uq_evaluator_assignment_active ON evaluator_assignment (proposal_id, evaluator_user_id) WHERE status IN ('ASSIGNED', 'ACCEPTED');

CREATE INDEX ix_outbox_event_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;

-- The optional one-PRINCIPAL-per-proposal policy is left to institutional configuration.
