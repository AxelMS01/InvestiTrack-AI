INSERT INTO research_area (id, code, name, active, created_at, created_by, updated_at, updated_by, version)
VALUES ('4594ec13-36ba-4c3a-a38e-592f57ad8d5e', 'TECNOLOGIA', 'Tecnología e innovación', true, now(),
        '00000000-0000-0000-0000-000000000000', now(), '00000000-0000-0000-0000-000000000000', 1)
ON CONFLICT (code) DO NOTHING;

INSERT INTO evaluation_criterion (id, code, name, description, minimum_score, maximum_score, weight,
                                  active_from, created_at, created_by, updated_at, updated_by, version)
VALUES ('ce3770d7-e4ed-4f87-9570-23291d9397b3', 'COHERENCIA', 'Coherencia metodológica',
        'Evalúa la relación entre el problema, los objetivos y el método.', 0, 10, 0.5, CURRENT_DATE,
        now(), '00000000-0000-0000-0000-000000000000', now(), '00000000-0000-0000-0000-000000000000', 1),
       ('a20ec07f-ca42-4a9a-9686-57bfe8883208', 'VIABILIDAD', 'Viabilidad',
        'Evalúa si la propuesta puede ejecutarse en el plazo indicado.', 0, 10, 0.5, CURRENT_DATE,
        now(), '00000000-0000-0000-0000-000000000000', now(), '00000000-0000-0000-0000-000000000000', 1)
ON CONFLICT (code) DO NOTHING;
