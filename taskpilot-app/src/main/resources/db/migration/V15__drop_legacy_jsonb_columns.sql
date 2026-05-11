-- V15__drop_legacy_jsonb_columns.sql

-- Drop the legacy JSONB columns from tasks table as we have migrated to relational task_labels and task_required_skills
ALTER TABLE tasks 
    RENAME COLUMN tags TO legacy_tags_do_not_use;

ALTER TABLE tasks 
    RENAME COLUMN required_skills TO legacy_skills_do_not_use;
