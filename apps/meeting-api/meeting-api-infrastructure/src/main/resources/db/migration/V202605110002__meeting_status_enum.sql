-- V202605110002: meeting_status enum type + meetings.status column type change
-- 对应 docs/spec-fixes.md §A10

BEGIN;

DO $$ BEGIN
  CREATE TYPE meeting_status AS ENUM ('CREATED','PROCESSING','SUCCEEDED','FAILED','DELETED');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE meetings
  ALTER COLUMN status DROP DEFAULT,
  ALTER COLUMN status TYPE meeting_status USING status::meeting_status,
  ALTER COLUMN status SET DEFAULT 'CREATED'::meeting_status;

COMMIT;
