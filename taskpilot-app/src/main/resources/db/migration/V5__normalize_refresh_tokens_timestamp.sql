-- Convert refresh_tokens timestamp columns to TIMESTAMP WITH TIME ZONE for consistency with V1 schema and timezone safety.
ALTER TABLE
   refresh_tokens
ALTER COLUMN
   expiry_date TYPE TIMESTAMP WITH TIME ZONE USING expiry_date AT TIME ZONE 'UTC',
ALTER COLUMN
   created_at TYPE TIMESTAMP WITH TIME ZONE USING created_at AT TIME ZONE 'UTC',
ALTER COLUMN
   updated_at TYPE TIMESTAMP WITH TIME ZONE USING updated_at AT TIME ZONE 'UTC';