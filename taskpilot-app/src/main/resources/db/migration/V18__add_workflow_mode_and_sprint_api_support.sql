ALTER TABLE "projects"
    ADD COLUMN IF NOT EXISTS "workflow_mode" VARCHAR(20) NOT NULL DEFAULT 'KANBAN';

CREATE INDEX IF NOT EXISTS "idx_sprints_project_status"
    ON "sprints" ("project_id", "status");

CREATE INDEX IF NOT EXISTS "idx_tasks_project"
    ON "tasks" ("project_id");

CREATE INDEX IF NOT EXISTS "idx_tasks_sprint"
    ON "tasks" ("sprint_id");
