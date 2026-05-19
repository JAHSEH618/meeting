-- Workstation D2: meeting-scoped glossary. Stored inline as jsonb to keep the join surface small.
-- Schema for each entry: {"term": string, "definition"?: string, "aliases"?: string[]}.

ALTER TABLE meetings
  ADD COLUMN IF NOT EXISTS glossary_terms jsonb NOT NULL DEFAULT '[]'::jsonb;

-- GIN supports containment / element queries when prompt-time hot-word bias arrives.
CREATE INDEX IF NOT EXISTS meetings_glossary_terms_idx
  ON meetings USING gin (glossary_terms);
