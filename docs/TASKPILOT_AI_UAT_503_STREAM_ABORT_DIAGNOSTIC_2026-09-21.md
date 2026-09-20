# TASKPILOT AI — UAT 503 STREAM ABORT DIAGNOSTIC REPORT

**Date:** 2026-09-21 01:17 (UTC+07:00)  
**Session:** `890`  
**Status:** **Investigation captured — no code/config changes made after this incident**

---

## 1. Incident Summary

During UAT of the AI chat flow, the system successfully entered the multi-agent workflow and streamed an intermediate reasoning response. The request then failed during the next LLM round with:

```text
com.openai.errors.InternalServerException: 503: null
```

After the failure, the SSE stream was closed/completed, and the UI showed a blank result. Database logging only retained the exception message; no useful trace of the partial assistant/reasoning output was persisted as a chat message.

The current evidence points to a **streaming failure / error-finalization path**, not to the retired-model problem investigated earlier.

---

## 2. Exact Runtime Timeline

### 01:17:17 — Tool execution completed

```text
01:17:17.254
[LocalCache] INVALIDATED session cache 890 due to write tool smartQuery
```

The `smartQuery` write invalidated the session cache as expected.

### 01:17:17 — Multi-agent round 0 completed

```text
01:17:17.375
[Multi-Agent] Round 0 complete. Streaming intermediate response via reasoning text model before next round for session 890
```

This is significant: the system had already completed one tool/agent round and intentionally attempted to stream an intermediate response before continuing.

### 01:17:17 — Tool result flattened into context

```text
01:17:17.377
[Sanitizer] Flattened AiMessage tool_calls into plain text.

01:17:17.377
[Sanitizer] Injected flattened Tool Result 'smartQuery' as Semantic Memory.
```

The tool result was converted into the next model context.

### 01:17:17 — Reasoning text model initialized

```text
01:17:17.493
[AI Config] Using OpenAI-compatible streaming endpoint for Gemini model: gemini-3.5-flash
```

This proves the runtime model path was using `gemini-3.5-flash` at this point. There is no evidence here of the previously retired `llama-3.3-70b-versatile` model being selected.

### 01:17:29 — Round 1 entered with dynamic tools

```text
01:17:29.114
[streamRound] Dynamic tools round=1 expanded=false model=gemini-3.5-flash (isGemmaModel=false)
-> injecting 23 tool specs
```

Round 1 started using `gemini-3.5-flash` with 23 tool definitions.

### 01:17:33 — Upstream LLM returned HTTP 503

```text
01:17:33.244 ERROR
[SSE] Model gemini-3.5-flash failed for session 890:
com.openai.errors.InternalServerException: 503: null
```

This is the primary failure event.

### 01:17:33 — Fallback model construction started

Immediately after the 503, the logs show multiple model initializations:

```text
01:17:33.418
[AI Config] Using OpenAI-compatible streaming endpoint for Gemini model: gemini-3.5-flash

01:17:33.419
[AI Config] Using OpenAI-compatible streaming endpoint for Gemini model: gemini-3.5-flash

01:17:33.430
[AI Config] Using OpenAI-compatible streaming endpoint for Gemini model: gemini-2.5-flash

01:17:33.431
[AI Config] Using OpenAI-compatible streaming endpoint for Gemini model: gemini-2.5-flash
```

This suggests the fallback/routing path was activated after the 503. The duplicate initialization messages should be investigated, but the log alone does not prove that a fallback request actually succeeded.

### 01:17:33 — SSE stream closed

```text
01:17:33.649 DEBUG
[SSE] AI chat stream completed/closed for session 890
```

Only ~405 ms after the 503, the backend reported the SSE stream as completed/closed.

This is consistent with the request reaching a terminal streaming error path rather than successfully recovering and continuing the original response.

---

## 3. What Is Confirmed

### 3.1 The model-path issue from the previous incident is not reproduced here

The failing model is:

```text
`gemini-3.5-flash`
```

The log does **not** show `llama-3.3-70b-versatile`.

The current incident is therefore a different failure class from the earlier retired-Groq-model problem.

### 3.2 The request had already produced intermediate workflow progress

The system explicitly logged:

```text
Round 0 complete.
Streaming intermediate response via reasoning text model...
```

Therefore the UI appearing to have "thinking/progress" before becoming blank is consistent with the server having entered a valid intermediate streaming phase before the later model call failed.

### 3.3 The failure is an upstream/service-side HTTP 503

The only concrete exception recorded for the failing model call is:

```text
InternalServerException: 503: null
```

The current log does not expose a more specific provider response body, request ID, or provider error code.

### 3.4 The SSE stream terminates after the model error

The log sequence is:

```text
503 error
→ fallback model initialization
→ SSE completed/closed
```

This establishes that the stream lifecycle reached a terminal close immediately after the failure sequence.

### 3.5 Durable chat history did not preserve the failed assistant output

Based on the observed DB state, the incident record contains only the exception information and no durable chat message representing the partial/intermediate assistant output.

This creates a visibility gap between:

```text
Transient SSE output
```

and

```text
Durable chat_messages / chat history
```

The exact persistence point that is missing still needs to be located in source.

---

## 4. Most Likely Failure Chain

The working hypothesis for tomorrow is:

```text
User AI request
      ↓
Round 0 / tool execution
      ↓
Intermediate response streaming begins
      ↓
Round 1 starts with gemini-3.5-flash + 23 tools
      ↓
Gemini-compatible endpoint returns HTTP 503
      ↓
StreamingChatEngine enters error handling / fallback path
      ↓
Fallback model(s) are initialized
      ↓
Original SSE request reaches terminal close
      ↓
No final assistant message is durably persisted
      ↓
Frontend loses transient streamed content
      ↓
UI appears blank
```

The key architectural question is **not only why Gemini returned 503**, but also:

> What does TaskPilot do with already-streamed content when the current LLM round fails before a final assistant message is persisted?

---

## 5. Main Investigation Areas for Tomorrow

### A. Trace the streaming error path

Start from:

```text
StreamingChatEngine
  → onError / error handler
  → fallback handling
  → stream termination
```

Determine:

- whether the current error path emits an SSE error event;
- whether it emits a final assistant message;
- whether it deliberately completes the emitter after failure;
- whether fallback is actually retried or only instantiated;
- whether the original exception is swallowed/reduced to `503: null`.

### B. Trace chat-message persistence

Locate exactly where assistant messages are persisted.

Determine whether persistence happens:

```text
before streaming
```

or

```text
after successful completion only
```

or

```text
at each intermediate/final stream phase
```

Then answer:

> Why does partial/intermediate output disappear from durable chat history when a later round fails?

### C. Trace intermediate-response persistence

Round 0 explicitly streams an intermediate response, but the observed DB state contains no corresponding durable chat message.

Determine whether intermediate responses are intentionally transient or whether persistence was expected but skipped.

Do not assume this is a bug until the intended persistence policy is identified.

### D. Inspect the frontend SSE failure handling

Verify what the frontend does when:

```text
onmessage / token stream
→ onerror / connection close
```

Questions:

- Does the frontend keep already-received tokens in local state?
- Does `onerror` clear the assistant message?
- Does connection close trigger a reset?
- Does the UI require a final `DONE`/completed event before rendering the assistant message permanently?

A backend 503 alone does not prove the blank UI is entirely server-side.

### E. Inspect fallback effectiveness

The logs show `gemini-3.5-flash` and `gemini-2.5-flash` initialization immediately after the failure.

Need to establish whether:

```text
fallback model was actually called
```

versus merely:

```text
fallback model bean/client was created
```

A useful next diagnostic is to correlate each fallback model initialization with an actual outgoing model-call log and its result.

### F. Investigate the 503 source

The current evidence is insufficient to determine whether the 503 came from:

- the Gemini provider;
- the OpenAI-compatible gateway/endpoint;
- a proxy/network layer;
- a request-size/tool-schema issue;
- transient provider unavailability;
- some internal client/gateway behavior.

The log currently exposes only:

```text
InternalServerException: 503: null
```

The next investigation should capture the complete exception/cause chain and, where available, the provider request/response metadata without logging secrets.

---

## 6. Important Secondary Observation: 23 Dynamic Tools

The failing round used:

```text
23 tool specs
```

with `gemini-3.5-flash`.

This is **not evidence by itself** that the tool count caused the 503.

However, because the failure happens exactly on the round where the model request is expanded with 23 tools, tool-schema size / request payload characteristics should be included in the investigation.

Do not conclude root cause from temporal correlation alone.

---

## 7. Why the Current DB Error Record Is Insufficient

The stored error:

```text
com.openai.errors.InternalServerException: 503: null
```

is inadequate for post-incident diagnosis because it does not reveal:

- exact provider/model path;
- whether the failure occurred on the first model call or a fallback call;
- round number;
- whether any tokens had already been emitted for that round;
- whether an SSE error event was sent;
- whether the connection closed normally or because of an exception;
- provider request ID / diagnostic metadata;
- whether fallback succeeded or failed.

The current logging therefore captures the exception but not enough **stream lifecycle context** around the exception.

---

## 8. Working Hypotheses (Do Not Treat as Confirmed Yet)

### Hypothesis 1 — Most important

The backend only persists an assistant message after successful completion. When the later LLM round fails, the transient streamed content is lost and no assistant message is saved.

**Evidence:** SSE output/progress is observed, followed by 503, followed by stream close, while DB history contains no corresponding assistant message.

**Still needs source verification.**

### Hypothesis 2

The frontend clears the currently streamed assistant content when the SSE connection errors/closes, producing the visible blank UI.

**Still needs frontend verification.**

### Hypothesis 3

The configured Gemini endpoint returned a transient HTTP 503 for the round-1 request.

**Confirmed only at HTTP-status level; underlying provider reason is unknown.**

### Hypothesis 4

The 23-tool request may contribute to the upstream failure through payload/tool-schema characteristics.

**Currently speculative. Must not be treated as root cause without evidence.**

---

## 9. What Should NOT Be Changed Tonight

No emergency refactor should be made before the failure path is understood.

In particular, do not immediately:

- rewrite the streaming engine;
- change RAG retrieval;
- redesign the model catalog;
- add new abstraction layers;
- change the number of tools globally;
- change database schema;
- add another provider solely to mask this incident.

First establish the actual failure and persistence lifecycle.

---

## 10. Evidence Snapshot

| Item | Observation | Confidence |
|---|---|---|
| Session | `890` | Confirmed |
| Failure time | `2026-09-21 01:17:33 +07:00` | Confirmed |
| Failing model | `gemini-3.5-flash` | Confirmed |
| HTTP error | `503` | Confirmed |
| Exception | `com.openai.errors.InternalServerException: 503: null` | Confirmed |
| Round | `1` | Confirmed |
| Dynamic tools | `23` | Confirmed |
| Round 0 completed | Yes | Confirmed |
| Intermediate streaming had started | Yes | Confirmed by backend log |
| Fallback path activated | Likely | Needs deeper verification |
| Fallback request succeeded | Unknown | Not shown in provided log |
| SSE closed after error | Yes | Confirmed |
| Partial output persisted to chat DB | Not observed | Confirmed from current observation |
| Exact provider reason for 503 | Unknown | Not available in current log |

---

## 11. Recommended Starting Point Tomorrow

The first debugging pass should trace one session end-to-end:

```text
session 890
   ↓
AiStreamingService
   ↓
StreamingChatEngine
   ↓
streamRound(round=1)
   ↓
model invocation
   ↓
503 exception
   ↓
onError / fallback
   ↓
SSE emitter completion
   ↓
assistant message persistence
   ↓
frontend SSE onerror/onclose
```

The goal of the first pass is to answer three concrete questions:

1. **Where exactly is the 503 generated?**
2. **Why does an already-streamed response leave no durable chat message?**
3. **Why does the client end up showing a blank assistant response instead of preserving the partial result and/or showing a recoverable error state?**

---

## 12. Incident Status

**Status:** Open — diagnosis captured for next work session.

**Current conclusion:**

> The retired-model runtime-path issue is not evident in this incident. The new failure is a `503` during `gemini-3.5-flash` round-1 streaming, followed by SSE termination. The highest-priority investigation is the interaction between the streaming error/fallback path and durable assistant-message persistence, followed by frontend SSE error handling and the true source of the upstream 503.

**No fixes applied after capturing this report.**
