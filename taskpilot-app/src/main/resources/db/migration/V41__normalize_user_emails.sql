-- Normalize existing user emails to lowercase and trimmed, safely disambiguating any legacy duplicates
WITH ranked_users AS (
    SELECT id, email,
           ROW_NUMBER() OVER (PARTITION BY LOWER(TRIM(email)) ORDER BY created_at ASC NULLS LAST, id ASC) as rn
    FROM users
    WHERE email IS NOT NULL
)
UPDATE users u
SET email = LOWER(TRIM(u.email)) || '_dup_' || u.id
FROM ranked_users r
WHERE u.id = r.id AND r.rn > 1;

UPDATE users
SET email = LOWER(TRIM(email))
WHERE email IS NOT NULL;

-- Create unique index on LOWER(TRIM(email)) to prevent case-variant duplicate emails
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_email_lower_trimmed ON users (LOWER(TRIM(email)));
