-- Optional DEVELOPMENT database setup. Do not run against production.
-- The created_by/updated_by values are external UUID references, not database foreign keys.
BEGIN;
INSERT INTO research_area
    (id, code, name, description, active, created_at, created_by, updated_at, updated_by, version)
VALUES
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'HTTP_TEST_AREA', 'HTTP test research area',
     'Development endpoint testing', true, now(), '11111111-1111-4111-8111-111111111111',
     now(), '11111111-1111-4111-8111-111111111111', 0)
ON CONFLICT (code) DO NOTHING;

INSERT INTO evaluation_criterion
    (id, code, name, description, minimum_score, maximum_score, weight, active_from,
     created_at, created_by, updated_at, updated_by, version)
VALUES
    ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'HTTP_TEST_MERIT', 'Scientific merit (HTTP test)',
     'Development endpoint testing', 1, 5, 1, current_date,
     now(), '11111111-1111-4111-8111-111111111111',
     now(), '11111111-1111-4111-8111-111111111111', 0)
ON CONFLICT (code) DO NOTHING;
COMMIT;
