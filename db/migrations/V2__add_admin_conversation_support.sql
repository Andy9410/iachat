ALTER TABLE conversations
    ADD COLUMN IF NOT EXISTS user_name VARCHAR(150),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;

UPDATE conversations c
SET user_name = COALESCE(NULLIF(user_name, ''), split_part(user_email, '@', 1))
WHERE user_email IS NOT NULL
  AND (user_name IS NULL OR user_name = '');

UPDATE conversations c
SET updated_at = COALESCE(
    (
        SELECT MAX(m.created_at)
        FROM messages m
        WHERE m.conversation_id = c.id
    ),
    c.created_at,
    NOW()
)
WHERE updated_at IS NULL;

ALTER TABLE conversations
    ALTER COLUMN updated_at SET DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_conversation_user
    ON conversations(user_email);

CREATE INDEX IF NOT EXISTS idx_conversation_updated
    ON conversations(updated_at DESC);
