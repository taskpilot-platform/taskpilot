# TaskPilot Project Architecture and Requirements Specification

## 1. System Overview
TaskPilot is an enterprise task and project management modular monolith designed to streamline agile workflows with an integrated AI copilot. The system organizes work across projects, sprints, tasks, comments, and members.

## 2. RAG Knowledge System
The RAG (Retrieval-Augmented Generation) subsystem enables the AI Assistant to query and synthesize information from project-specific documents such as architecture diagrams, software requirement specifications, and API designs.

### 2.1 Technical Stack
- Backend: Java 25, Spring Boot 4.1.0, Spring Data JPA, Hibernate 7.4
- Database: PostgreSQL 17.6 with pgvector extension
- Parsing: Apache Tika document text extraction
- Embedding Model: Google gemini-embedding-2 canonical 768-dimensional model
- Vector Search: Cosine distance indexing using HNSW (m=16, ef_construction=64)

### 2.2 Reliability and Ingestion Guarantees
- Durable Job Queue in PostgreSQL with atomic claiming via FOR UPDATE SKIP LOCKED
- Resumable ingestion powered by document_chunk_staging table
- Upfront text chunk persistence before external embedding calls
- Processing version fencing protecting against stale or zombie worker overwrite
- Dual-dimension quota admission limiter enforcing 100 RPM and 30,000 TPM
- Protected interactive headroom ensuring real-time chat queries never starve
- Atomic publication into document_chunks within a locked transaction

### 2.3 Role-Based Access Control (RBAC) & Tenant Protection
- **Project Manager (`MANAGER`)**: Full administrative write authority over project knowledge (Upload documents, trigger re-indexing/retry, delete documents, and perform semantic search).
- **Project Member (`MEMBER`)**: Read-only access to project knowledge (List documents, inspect document status and metadata, perform semantic search in Knowledge UI, and leverage AI Copilot RAG retrieval). Write mutations (`POST /upload`, `POST /retry`, `DELETE /:docId`) are strictly forbidden with HTTP 403 Forbidden.
- **Non-Members**: Strict multi-tenant security gate denying all access with HTTP 403 Forbidden prior to executing any embedding or vector database query.
- **Defense-in-Depth UI**: Frontend dynamically adapts to user role—displaying administrative dropzone and management actions to Managers, while presenting an informative read-only guidance banner and hiding mutation controls for Members.
