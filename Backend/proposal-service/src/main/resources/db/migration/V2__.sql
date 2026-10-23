CREATE TABLE research_area
(
    id          UUID                     NOT NULL,
    code        VARCHAR(50),
    name        VARCHAR(200)             NOT NULL,
    description TEXT,
    active      BOOLEAN                  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by  UUID                     NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by  UUID                     NOT NULL,
    version     BIGINT                   NOT NULL,
    CONSTRAINT pk_research_area PRIMARY KEY (id)
);

ALTER TABLE research_area
    ADD CONSTRAINT uq_research_area_code UNIQUE (code);