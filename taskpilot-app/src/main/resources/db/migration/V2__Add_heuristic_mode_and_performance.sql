CREATE TYPE "heuristic_mode" AS ENUM ('BALANCED', 'URGENT', 'TRAINING');

ALTER TABLE "projects"
    ADD COLUMN "heuristic_mode" heuristic_mode DEFAULT 'BALANCED';

ALTER TABLE "sprints"
    ADD COLUMN "heuristic_mode" heuristic_mode DEFAULT NULL;
COMMENT
ON COLUMN "sprints"."heuristic_mode" IS 'Ghi đè chiến lược AI cho riêng Sprint này (Nếu NULL thì lấy của Project)';

ALTER TABLE "project_members"
    ADD COLUMN "performance_score" FLOAT DEFAULT 0.5;
