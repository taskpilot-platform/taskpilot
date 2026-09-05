# TaskPilot RAG Architecture Reference

## System Topology & Subsystem Boundaries

```text
[ Client / Web UI ]
       │  (Upload Doc / Stream Chat)
       ▼
┌─────────────────────────────────────────────────────────────┐
│                      TaskPilot Backend                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [ Ingestion Flow ]                                         │
│  ProjectDocumentController                                  │
│         │                                                   │
│         ▼                                                   │
│  DocumentIngestionService                                   │
│         │ 1. Download Stream                                │
│         ├──► StorageService (S3StorageServiceImpl)          │
│         │ 2. Text Extraction (PDF, DOCX, TXT, MD, CSV)      │
│         ├──► Apache Tika TextExtractor                      │
│         │ 3. Recursive Text Chunking (~700 chars / 100 ovl) │
│         ├──► DocumentChunker (DocumentSplitters.recursive)  │
│         │ 4. Vector Embedding (768-dim)                     │
│         ├──► EmbeddingService (Google gemini-embedding-2)   │
│         │ 5. Persistence                                    │
│         └──► DocumentChunkRepository (PostgreSQL pgvector)  │
│                                                             │
│  [ Query / Retrieval Flow ]                                 │
│  StreamingChatEngine (LLM: Gemini 3.8 Flash)                │
│         │                                                   │
│         ▼                                                   │
│  TaskPilotAiTools.searchProjectKnowledge                    │
│         │                                                   │
│         ▼                                                   │
│  ProjectKnowledgeService                                    │
│         │ 1. Authorization Gate (reject with 403 before API)│
│         ├──► ProjectSecurityService.validateMember(...)     │
│         │ 2. Embed Query (768-dim)                          │
│         ├──► EmbeddingService.embedQuery(query)             │
│         │ 3. Nearest-Neighbor Search (pgvector HNSW <=>)    │
│         └──► DocumentChunkRepository.findNearestChunks(...) │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Ingestion Sequence
1. User uploads document via REST endpoint specifying `projectId`.
2. Server validates user is active member/manager of `projectId`.
3. File is uploaded to S3 storage bucket; `DocumentEntity` is persisted with status `UPLOADING` -> `PROCESSING`.
4. File stream is pulled from S3 and passed to Apache Tika parser.
5. Plain text is cleaned and split into recursive text chunks (~700 chars with ~100 char overlap).
6. Chunks are converted to 768-dim float vectors using canonical `gemini-embedding-2` (`outputDimensionality = 768`).
7. Chunks and embeddings are batch-inserted into `document_chunks` with `project_id` and `document_id`.
8. Document status is updated to `READY`. If any failure occurs, status is set to `FAILED` and any partial chunks are deleted.

## Retrieval Sequence
1. LLM decides to call `searchProjectKnowledge(projectId, query)`.
2. Adapter extracts authenticated `userId` from `ToolExecutionContext.requireUserId()`.
3. Validates membership of `userId` in `projectId`. Rejected with `403 Forbidden` if unauthorized before external embedding API calls occur.
4. Generates 768-dim vector embedding of `query` using canonical `gemini-embedding-2`.
5. Executes SQL query against `document_chunks` filtering on `project_id = :projectId` ordered by `embedding <=> :queryVector` with LIMIT `topK`.
6. Formats retrieved chunks and returns as tool result to LLM.
