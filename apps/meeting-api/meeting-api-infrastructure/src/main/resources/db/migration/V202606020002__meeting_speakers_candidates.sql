ALTER TABLE meeting_speakers
  ADD COLUMN IF NOT EXISTS candidates jsonb NOT NULL DEFAULT '[]'::jsonb;
