# TaskPilot RAG Verification Scripts

This directory contains repeatable scripts for verifying RAG subsystem builds, migrations, and tenant isolation.

## Script Inventory
- `verify-rag-build.ps1`: Compiles all modules with preview flags enabled.
- `verify-rag-tests.ps1`: Runs unit and integration test suites for RAG and AI components.
- `verify-rag-isolation.ps1`: Executes multi-tenant isolation tests verifying that users cannot access out-of-scope project knowledge.

All scripts adhere to the rule: return exit code 0 on success and non-zero on failure.
