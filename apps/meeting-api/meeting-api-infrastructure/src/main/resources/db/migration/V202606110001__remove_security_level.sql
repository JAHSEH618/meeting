-- V202606110001__remove_security_level.sql
-- Phase K: Remove security_level column from meetings table
-- All meetings can now use LLM features without security level restrictions

ALTER TABLE meetings DROP COLUMN IF EXISTS security_level;
