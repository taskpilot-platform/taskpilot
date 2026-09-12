# Modern Spring Data Feature Implementation Ledger

| Modern Feature | Target Class / Interface | Location | Status | Optimization Impact |
| :--- | :--- | :--- | :---: | :--- |
| **11. getReferenceById** | `UserSkillModuleAdapter` | `taskpilot-users` |  COMPLETED | Eliminates redundant `SELECT * FROM users` when linking user skills |
| **11. getReferenceById** | `SkillService` | `taskpilot-users` |  COMPLETED | Eliminates redundant `SELECT` during self-service skill enrollment |
| **4. Keyset Window** | `ChatMessageRepository` | `taskpilot-ai` |  COMPLETED | Infinite scroll chat history with O(1) indexed seeks & 0 COUNT queries |
| **4. Keyset Window** | `NotificationRepository` | `taskpilot-users` |  COMPLETED | In-app notification drawer streaming with keyset scrolling |
| **6. @Meta Comments** | `ChatMessageRepository` | `taskpilot-ai` |  COMPLETED | SQL query tracing for AI chat history & session counting |
| **6. @Meta Comments** | `ChatSessionRepository` | `taskpilot-ai` |  COMPLETED | SQL query tracing for session title updates |
| **6. @Meta Comments** | `AiLogRepository` | `taskpilot-ai` |  COMPLETED | SQL query tracing for recent AI model telemetry logs |
| **6. @Meta Comments** | `CommentRepository` | `taskpilot-projects` |  COMPLETED | SQL query tracing for multi-criteria comment search & participants |
| **6. @Meta Comments** | `ProjectMemberRepository`| `taskpilot-projects` |  COMPLETED | SQL query tracing for membership checks, scores, and cleanups |
| **6. @Meta Comments** | `TaskRepository` | `taskpilot-projects` |  COMPLETED | SQL query tracing for bulk sprint detachment & project deletions |
| **6. @Meta Comments** | `TaskLabelRepository` | `taskpilot-projects` |  COMPLETED | SQL query tracing for batch task label resolution |
| **6. @Meta Comments** | `TaskRequiredSkillRepository` | `taskpilot-projects` |  COMPLETED | SQL query tracing for batch skill requirements |
| **6. @Meta Comments** | `UserSkillRepository` | `taskpilot-users` |  COMPLETED | SQL query tracing for fetch-joined user profile skills |
| **6. @Meta Comments** | `UserRepository` | `taskpilot-users` |  COMPLETED | SQL query tracing for user search queries |
| **6. @Meta Comments** | `NotificationRepository`| `taskpilot-users` |  COMPLETED | SQL query tracing for batch notification read updates |
| **6. @Meta Comments** | `SystemSettingRepository`| `taskpilot-users` |  COMPLETED | SQL query tracing for administrative system config search |
| **5. @NativeQuery** | `TaskRepository` | `taskpilot-projects` |  COMPLETED | Dedicated annotation for native PostgreSQL status summary |
| **5. @NativeQuery** | `AiLogRepository` | `taskpilot-ai` |  COMPLETED | Dedicated annotation for native PostgreSQL log telemetry aggregation |
| **7. JpaSort.unsafe** | `JpaSortUtils` | `taskpilot-infrastructure`|  COMPLETED | DB function sorting (e.g. `NULLS LAST`) without PropertyReferenceException |
| **10. Fragment Comp.**| `TaskSearchFragment` | `taskpilot-projects` |  COMPLETED | Modular fragment decomposition for specialized search routines |
