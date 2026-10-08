---
name: taskpilot-workspace-collab
description: Guide and architectural invariants for developing, maintaining, and verifying the Workspace Collaboration subsystem (Project File Storage and Real-time Chat Room) in TaskPilot using PostgreSQL, S3 Storage, and Spring WebSocket STOMP.
---

# TaskPilot Workspace Collaboration Subsystem Skill

## 1. Overview & Scope

The Workspace Collaboration subsystem delivers Phase 3 (P2 in the Thesis 2 Outline) capabilities:
1. **Project File Storage (`Workspace: Files`)**: Project-scoped document and asset management, supporting multipart upload, secure S3 streaming download, file search, and role-based deletion.
2. **Real-time Chat Room (`Workspace: Chat`)**: Dedicated project-level real-time communication room backed by Spring WebSocket with STOMP protocol, durable PostgreSQL history persistence, and authenticated participant verification.

---

## 2. Architectural Invariants & Constraints

### 2.1 Modular Monolith Compliance
- **Zero External Brokers**: Chat room messaging uses in-process Spring STOMP simple broker (`/topic`). Do NOT introduce external Kafka, RabbitMQ, or Redis pub/sub.
- **Tenant & Membership Boundary**: Every file operation and chat message is bounded strictly by `projectId`. Users MUST be verified active members of the target project (`ProjectMemberPort.isProjectMember(projectId, userId)`).

### 2.2 Storage & File Management
- Files are persisted in Supabase S3 Object Storage under the `documents` or dedicated `project-files` bucket with prefix `projects/{projectId}/files/{uuid}_{originalName}`.
- Metadata is tracked in `project_files` with size, MIME type, uploader identity, and timestamps.
- **RBAC**:
  - `UPLOAD`, `DOWNLOAD`, `LIST`: Any active project member.
  - `DELETE`: The original file uploader OR a Project Manager/Admin.

### 2.3 Real-time Chat Room Protocol
- **STOMP Endpoint**: `/ws/chat` (with SockJS fallback).
- **Subscribe Destination**: `/topic/projects/{projectId}/chat`
- **Publish Destination**: `/app/projects/{projectId}/chat.sendMessage`
- **History REST Endpoint**: `GET /api/v1/projects/{projectId}/chat/messages` with pagination (`page`, `size`).
- **Durable Persistence**: Every chat message is committed to `project_chat_messages` before or during WebSocket broadcast.

---

## 3. Engineering & Verification Workflow

1. **Database Schema**: Forward-only Flyway migration `V32__create_project_files_and_chat_messages_tables.sql`.
2. **Backend Contracts & Services**: Clean Ports & Adapters separation in `taskpilot-projects`.
3. **Frontend Components**: TypeScript React components with clean UX, loading states, error boundaries, and WebSocket reconnection guards.
4. **Automated Verification**:
   - Backend unit & mock tests verifying upload, download, delete RBAC, and chat broadcasting.
   - Frontend Vitest tests ensuring file listing, upload triggers, and chat message rendering.
5. **Documentation & Tracking**:
   - Progress recorded in `progress-tracker/PROGRESS_INDEX.md` and `logs/period-2026-w40-w41.md`.
