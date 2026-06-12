-- V202606120830__remove_security_level_remnants.sql
-- Phase K follow-up: remove the remaining security_level columns after
-- V202606110001 dropped meetings.security_level. Also fixes the NOT NULL
-- violation on llm_call_logs writes (the Java INSERT omits the column).
-- With every dependent column gone, the security_level enum type is dropped.

ALTER TABLE documents DROP COLUMN IF EXISTS security_level;
ALTER TABLE knowledge_chunks DROP COLUMN IF EXISTS security_level;
ALTER TABLE llm_call_logs DROP COLUMN IF EXISTS security_level;
ALTER TABLE llm_data_boundary_logs DROP COLUMN IF EXISTS security_level;

DROP TYPE IF EXISTS security_level;
