# AI EXTRACTION FEASIBILITY AUDIT

## Goal: Can `taskpilot-ai` become an independent service?

==================================================
## STEP 1: DEPENDENCY INVENTORY
==================================================

The `taskpilot-ai` module is an orchestration layer. It owns almost no business data (only chat logs) and relies entirely on external domains to function. 

**Dependencies to Users Module:**
* `UserIdentityPort` (used in `AiChatController.java` to validate auth tokens/sessions)
* `UserProfilePort` (used in `AiStreamingService.java` to attach user profile context to LLM memory)
* `UserPort`, `UserSkillPort` (used in `AutoAssignmentService.java` to fetch candidates)
* `SkillPort`, `UserNotificationQueryPort` (used in `TaskPilotAiTools.java` for skill searching and notification fetching)
* `SystemSettingPort` (used in `HeuristicConfigProvider.java` to fetch AI algorithm weights)

**Dependencies to Projects Module:**
* `ProjectMemberPort`, `ProjectPort` (used in `AutoAssignmentService.java`)
* `ProjectInsightsPort`, `MemberAnalyticsPort`, `SprintQueryPort`, `TaskCommandPort`, `TaskCommentQueryPort` (used extensively across 60+ tools in `TaskPilotAiTools.java`)

**Dependencies to Infrastructure:**
* `BusinessException`, `ApiResponse` (used across controllers and services)
* `BaseEntity` (extended by `ChatSessionEntity`)

==================================================
## STEP 2: TOOL CALL ANALYSIS
==================================================

`TaskPilotAiTools.java` registers over 60 `@Tool` methods for the LLM. Every single one of these tools requires querying the core monolith database.

**Worst-Case Reasoning Chain (Network Call Explosion)**
If the user asks: *"Assign the hardest unassigned task in Project X to the most available Java expert"*

1. LLM decides it needs to find unassigned tasks.
   * **Call 1:** `getUnassignedTasksByProject(projectId)` ➔ HTTP to Projects Service
2. LLM decides it needs project members to evaluate workload.
   * **Call 2:** `getProjectMembers(projectId)` ➔ HTTP to Projects Service (which fetches from Users)
3. LLM needs workload metrics for the team.
   * **Call 3:** `getMemberWorkload(projectId)` ➔ HTTP to Projects Service
4. LLM needs to ensure "Java" is a valid system skill.
   * **Call 4:** `searchSystemSkills("Java")` ➔ HTTP to Users Service
5. LLM runs heuristic, picks the best candidate, and assigns the task.
   * **Call 5:** `assignTaskToMember(taskId, userId)` ➔ HTTP to Projects Service
   
**Impact:** A single conversational prompt generates **5 internal network round-trips** before the LLM can stream the final text back to the user.

==================================================
## STEP 3: LATENCY ANALYSIS
==================================================

**Current Latency (In-Process Monolith):**
* Method calls via interfaces (`Ports`) take **< 1ms**.
* Database queries execute within the same Hikari connection pool.
* LLM tool execution overhead is negligible. The bottleneck is strictly the external LLM provider (e.g., Gemini API).

**Remote AI Service Latency (Extracted):**
* HTTP/REST or gRPC serialization overhead: **~10-20ms per call**.
* Network routing and auth validation per internal hop.
* 5 tool calls = **100ms+ added latency** blocking the LLM reasoning thread.
* **Bottleneck:** The LangChain agent blocks LLM streaming while waiting for tool responses. High internal network latency will result in painful "thinking..." delays for end-users on the frontend.

==================================================
## STEP 4: FAILURE ANALYSIS
==================================================

If extracted, the system's fault domains become fractured:

* **If AI service unavailable:** 
  * The AI chat panel breaks.
  * Core operations (UI tasks, sprints, users) **continue to work normally** since they don't depend on AI. 
* **If User service unavailable:** 
  * The AI Chat completely breaks. `AiChatController` cannot authenticate the user via `UserIdentityPort`.
  * The LLM hallucinates because `UserProfilePort` fails to return context.
* **If Project service unavailable:**
  * The LLM chat UI loads, but **every tool call fails**. The LLM will repeatedly reply with *"I'm sorry, I cannot access the project data right now."*
  * AI logs become inconsistent (attempting to record a log for a `project_id` that the AI cannot verify exists).

==================================================
## STEP 5: FINAL VERDICT
==================================================

**Recommendation: A. Keep AI in Monolith**

**Evidence & Justification:**
The `taskpilot-ai` module is not a bounded context; it is an **Orchestrator**. It owns no core business data. Its entire purpose is to read from and write to the Projects and Users domains via function calling. 

If extracted into a microservice, you are forced to build a massive internal API layer just to expose the monolith's internal state to the AI service. You will trade in-process method calls for high-latency, failure-prone network hops in the middle of LLM reasoning loops. 

The AI module is heavily CPU/Memory bound (due to memory windows and streaming), whereas the CRUD operations are I/O bound. The correct scaling strategy is not to split the codebase into microservices, but to simply deploy the monolith, rely on the load balancer, and let the monolithic runtime handle the orchestrations locally where they are fastest.