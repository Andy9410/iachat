ALTER TABLE whiteboard_entries
  ADD COLUMN IF NOT EXISTS author TEXT NOT NULL DEFAULT 'assistant';

-- user = written by the student, assistant = written by the AI
