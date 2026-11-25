-- Audit Service checks this role against current Identity state. It grants no
-- project, proposal, user-management, document, or AI capabilities.
insert into app_role(id,code,name,description,created_at,created_by,updated_at,updated_by)
values('b956542c-f1a7-55fe-bc8f-6414f7790771','AUDITOR','Auditor','Institution-wide audit history and export access',current_timestamp,
'00000000-0000-0000-0000-000000000000',current_timestamp,'00000000-0000-0000-0000-000000000000');
