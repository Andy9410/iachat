ALTER TABLE conversations ADD COLUMN IF NOT EXISTS user_email VARCHAR(150);

CREATE INDEX IF NOT EXISTS idx_conversations_user_email ON conversations(user_email);
