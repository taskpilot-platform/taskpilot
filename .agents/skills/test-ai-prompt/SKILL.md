---
name: taskpilot-ai-tester
description: Test the TaskPilot AI prompt and form generation behavior
---

# TaskPilot AI Tester

This skill tests the backend AI streaming service, tool routing, form generation, and human-in-the-loop CUD behavior.

## Primary CUD test runner

Use the full CUD runner when asked to verify whether AI tools can really create, update, or delete data:

```bash
node /home/fhu_thjen/projects/se121/scripts/ai-cud-tool-test.js
```

The runner:

- Logs in as `dangphuthien2005@gmail.com` / `12345678` by default.
- Creates an AI chat session.
- Sends real prompts through `POST /api/v1/ai/sessions/{sessionId}/stream` using body field `message`.
- Parses SSE events named `tool`, `phase`, and `done`.
- Treats CUD as successful only after the write tool returns `confirmationRequired=true`, a second confirmation prompt is sent, a confirm tool runs, and REST reads prove the database changed.
- Creates isolated test data, verifies DB effects, then cleans up through AI CUD tools when possible.
- Writes reports to `/home/fhu_thjen/projects/se121/scripts/reports`.

Useful environment variables:

```bash
BASE_URL=http://localhost:8080/api/v1 \
TEST_EMAIL=dangphuthien2005@gmail.com \
TEST_PASSWORD=12345678 \
SCENARIOS=all \
node /home/fhu_thjen/projects/se121/scripts/ai-cud-tool-test.js
```

Optional data-dependent variables:

- `TEST_PROJECT_CODE`: invitation code used to test `joinProject`.
- `TEST_MEMBER_PROJECT_ID`: project ID used to test `leaveProject`, `updateMemberRole`, and `removeMember`.
- `TEST_TARGET_USER_ID`: target member user ID used to test `updateMemberRole` and `removeMember`.
- `SKIP_CLEANUP=1`: leave generated test data in the database for debugging.
- `REPORT_DIR=/tmp/taskpilot-ai-reports`: override report output location.

`SCENARIOS` may be a comma-separated list such as:

```bash
SCENARIOS=project.create,task.create,task.patchStatusMove
```

The full runner covers project, task, sprint, comment, label, assignment, required-skill, system-skill, personal-skill, notification, membership, member-admin, routing, and confirmation-safety scenarios. Data-dependent or permission-dependent scenarios must report `blocked_by_data` when the required seed data is unavailable.

## Result statuses

Record and report these statuses instead of silently passing a broken flow:

- `pass`: expected tool ran, confirmation ran, and DB changed as expected.
- `cannot_cud`: assistant says it cannot perform the operation even though a tool should exist.
- `wrong_tool`: expected tool was not called.
- `no_pending_confirmation`: write tool ran but did not return a confirmation action.
- `confirm_tool_not_called`: second confirmation prompt did not call `confirmPendingAction` or `confirmLatestPendingAction`.
- `confirmation_loop`: confirmation prompt caused the agent to ask for confirmation again instead of executing.
- `no_db_effect`: confirmation ran, but REST verification did not show the expected database change.
- `cud_before_confirmation`: data changed before explicit confirmation, which is a safety failure.
- `blocked_by_data`: scenario needs seed data, permissions, or project members that are unavailable.
- `cleanup_failed`: test data may remain and should be cleaned manually.

## Key CUD Architecture Fixes (Verified)

1. **`patchJson` & `patchData` Normalization**:
   - `TaskPilotAiTools.java` uses `normalizePatch()` helper to handle double-stringified or unquoted JSON from LLMs.
   - `ToolCallingRegistryService.java` automatically maps `patch` or `patchJson` to `patchData` across all patch tools.

2. **Null Parameter Handling**:
   - Optional parameters in `createTask` and other CUD tools are null-sanitized during tool argument normalization.

3. **Cascade Project Deletion**:
   - Project deletion cascades through child entities (`tasks`, `sprints`, `labels`, `members`) preventing HTTP 500 errors.

4. **Spring Constructor Auto-wiring**:
   - `TaskPilotAiTools` uses explicit `@Autowired` constructor to prevent `BeanInstantiationException` when multiple constructors exist.

## Form-only check

For the lightweight form test, the important behavior is:

- Prompt: `tạo task`.
- `getMyProjects` must be called before a `taskpilot-form` is returned.
- ID fields such as `projectId`, `sprintId`, and `assigneeId` must use `type: "select"` with options.
- `sprintId`, `difficultyLevel`, `startDate`, and `dueDate` must not be required for `createTask`.

Legacy scripts in `/home/fhu_thjen/projects/se121/scripts/ai-test-agent*.js` are ad-hoc probes. Prefer `ai-cud-tool-test.js` for CUD verification because the legacy scripts do not verify database effects after confirmation.
