# TaskPilot Reusable Agent & Skill Knowledge Base

This directory (`.agents/`) is the **single canonical repository-owned source of truth** for reusable AI engineering intelligence, architectural playbooks, workflows, and skills across the TaskPilot codebase.

---

## 1. Purpose of `.agents/`

The `.agents/` directory ensures that all engineering workflows, architecture guidelines, refactoring rules, and domain-specific AI skills:
- Are committed and version-controlled directly within the Git repository;
- Are completely portable: any engineer or AI agent cloning TaskPilot has immediate access to the exact same intelligence;
- Prevent fragmentation, machine-local configuration drift, and duplicate prompt copies.

---

## 2. Directory Layout & Terminology

```text
.agents/
├── README.md                                    # Canonical guide and policies (this file)
└── skills/                                      # Reusable specialized skills
    ├── ai-module-refactor/                      # AI module refactoring playbooks
    ├── biweekly-progress-tracker/               # Progress tracking & report templates
    ├── code-refactor/                           # General code quality & refactor standards
    ├── context7/                                # Up-to-date documentation retrieval via Context7 API
    ├── godfile-refactor/                        # Decomposing large god-classes safely
    ├── hf-deploy-verifier/                      # HuggingFace & model deployment verification
    ├── mobile-responsive-test/                  # Mobile responsive testing scripts & checklists
    ├── spring-data-modernization/               # Spring Data & JPA modernization playbooks
    ├── taskpilot-architecture-maintenance/      # Architecture maintenance, reconciliation & P0 protocol
    ├── taskpilot-rag/                           # RAG ingestion, rate limiting, and pgvector operations
    └── test-ai-prompt/                          # AI prompt testing workflows
```

### Distinction of Concepts
- **Skills (`skills/<skill-name>/SKILL.md`)**: Self-contained operational procedures, checklists, scripts, and rules for specialized workflows (e.g., RAG operations, architecture refactoring, dependency upgrades).
- **Agents (`agents/`)**: Future declarative agent definitions specifying persistent personas, role boundaries, and tool whitelist policies.
- **Prompts (`prompts/`)**: Future standardized system prompts, few-shot evaluations, and persona templates.

---

## 3. How to Add a New Reusable Skill

When introducing a new specialized engineering workflow:
1. Create a dedicated folder: `.agents/skills/<kebab-case-skill-name>/`.
2. Author a `SKILL.md` file with YAML frontmatter:
   ```yaml
   ---
   name: <kebab-case-skill-name>
   description: <Concise, actionable description of when and how to invoke this skill>
   ---
   ```
3. Include clear operational sections:
   - Purpose & triggers (when to use / when NOT to use);
   - Core invariants and boundary constraints;
   - Step-by-step workflow;
   - Self-healing test and verification commands.
4. Place supporting reference documents in `references/`, scripts in `scripts/`, or checklists in `checklists/`.
5. Keep the skill **generic and reusable**; avoid hardcoding single-use ticket or temporary patch data.

---

## 4. Strict Policies & Invariants

1. **Zero Competing Duplicates**:
   - Never create parallel `.agent/` (singular) directories or duplicate skills across personal machine folders.
   - All shared skills live under `.agents/`.
2. **Never Commit Secrets or Machine-Local State**:
   - API keys, personal access tokens, developer credentials, session caches, and temporary scratchpads MUST NEVER be placed in `.agents/`.
   - `.agents/` is strictly for version-controlled documentation, rules, and deterministic helper scripts.
3. **Keep Modular Monolith Boundaries**:
   - Skills must enforce ADR-001: TaskPilot is a modular monolith. Avoid instructions that advocate distributed complexity (Kafka/Redis/microservices) unless verified by project requirements.

---

## 5. Agent Discovery Protocol

Any AI assistant or engineer operating in the TaskPilot repository should:
1. Discover all available skills by scanning `.agents/skills/*/SKILL.md`.
2. Read the appropriate `SKILL.md` before initiating architecture changes, RAG maintenance, or refactor tasks.
3. Treat `.agents/` as the single canonical source of truth over any external or historical conversation memory.
