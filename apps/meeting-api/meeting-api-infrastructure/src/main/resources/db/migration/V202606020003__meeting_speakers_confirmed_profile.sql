ALTER TABLE meeting_speakers
  ADD COLUMN IF NOT EXISTS confirmed_speaker_profile_id text REFERENCES speaker_profiles(id);
