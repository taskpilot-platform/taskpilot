-- P2: Account lifecycle, email-bound credentials/challenges, auth attempt store,
-- recovery outbox, project management attention audit, and private invitations.

ALTER TABLE public.oauth2_authorization_requests
    DROP CONSTRAINT IF EXISTS chk_oauth2_auth_req_purpose,
    DROP CONSTRAINT IF EXISTS chk_oauth2_auth_req_link_user,
    ADD CONSTRAINT chk_oauth2_auth_req_purpose CHECK (purpose IN ('LOGIN', 'LINK', 'REAUTH')),
    ADD CONSTRAINT chk_oauth2_auth_req_link_user CHECK (
        (purpose = 'LOGIN' AND target_user_id IS NULL)
        OR (purpose IN ('LINK', 'REAUTH') AND target_user_id IS NOT NULL)
    );

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS email_verification_provenance VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS lifecycle_changed_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS lifecycle_reason VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS lifecycle_changed_by BIGINT NULL,
    ADD COLUMN IF NOT EXISTS legacy_review_required BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE public.users
SET account_state = 'LEGACY_DISABLED',
    legacy_review_required = TRUE,
    lifecycle_reason = 'LEGACY_DEACTIVATED_AMBIGUOUS'
WHERE status = 'DEACTIVATED'
  AND account_state IN ('ACTIVE', 'LEGACY_DISABLED');

ALTER TABLE public.user_identities
    ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS verification_provenance VARCHAR(64) NULL;

ALTER TABLE public.password_reset_tokens
    ALTER COLUMN token DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'PASSWORD_RESET',
    ADD COLUMN IF NOT EXISTS credential_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS target_provider VARCHAR(32) NULL,
    ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS invalidated_at TIMESTAMPTZ NULL;

UPDATE public.password_reset_tokens
SET is_used = TRUE,
    invalidated_at = NOW()
WHERE token_hash IS NULL
  AND is_used = FALSE;

ALTER TABLE public.password_reset_tokens
    ADD CONSTRAINT chk_password_reset_tokens_purpose CHECK (
        purpose IN ('EMAIL_VERIFY', 'PASSWORD_SETUP', 'PASSWORD_RESET', 'RESTORE', 'CREDENTIAL_LINK', 'REAUTH_PROOF')
    ),
    ADD CONSTRAINT chk_password_reset_tokens_cred_ver_non_negative CHECK (credential_version >= 0);

CREATE UNIQUE INDEX ux_password_reset_tokens_token_hash
    ON public.password_reset_tokens (token_hash)
    WHERE token_hash IS NOT NULL;

CREATE INDEX idx_password_reset_tokens_user_purpose
    ON public.password_reset_tokens (user_id, purpose);

CREATE INDEX idx_password_reset_tokens_expiry
    ON public.password_reset_tokens (expiry_date);

CREATE TABLE public.auth_attempt_counters (
    bucket_key         VARCHAR(128) PRIMARY KEY,
    action             VARCHAR(32)  NOT NULL,
    key_hash           VARCHAR(64)  NOT NULL,
    window_started_at  TIMESTAMPTZ  NOT NULL,
    expires_at         TIMESTAMPTZ  NOT NULL,
    attempt_count      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT chk_auth_attempt_counters_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_auth_attempt_counters_expires_at
    ON public.auth_attempt_counters (expires_at);

REVOKE ALL ON TABLE public.auth_attempt_counters FROM PUBLIC;
ALTER TABLE public.auth_attempt_counters ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_auth_attempt_counters
    ON public.auth_attempt_counters
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);

CREATE TABLE public.user_lifecycle_recovery_outbox (
    event_id        UUID         PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    token_version   BIGINT       NOT NULL,
    lifecycle       VARCHAR(32)  NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    attempt_count   INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    lease_until     TIMESTAMPTZ  NULL,
    last_error      VARCHAR(500) NULL,
    reconciled_at   TIMESTAMPTZ  NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_lifecycle_recovery_outbox_user FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT chk_user_lifecycle_recovery_outbox_status CHECK (status IN ('PENDING', 'RECONCILED', 'FAILED_PERMANENT'))
);

CREATE INDEX idx_user_lifecycle_recovery_outbox_pending
    ON public.user_lifecycle_recovery_outbox (status, next_attempt_at);

CREATE INDEX idx_user_lifecycle_recovery_outbox_user_version
    ON public.user_lifecycle_recovery_outbox (user_id, token_version);

REVOKE ALL ON TABLE public.user_lifecycle_recovery_outbox FROM PUBLIC;
ALTER TABLE public.user_lifecycle_recovery_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_user_lifecycle_recovery_outbox
    ON public.user_lifecycle_recovery_outbox
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);

ALTER TABLE public.projects
    ADD COLUMN IF NOT EXISTS roster_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS needs_manager_attention BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS management_attention_reason VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS management_attention_since TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS management_attention_event_id UUID NULL;

ALTER TABLE public.projects
    ADD CONSTRAINT chk_projects_roster_version_non_negative CHECK (roster_version >= 0);

CREATE TABLE public.project_management_attention_audit (
    event_id            UUID        PRIMARY KEY,
    project_id          BIGINT      NOT NULL,
    suspended_user_id   BIGINT      NULL,
    user_token_version  BIGINT      NULL,
    event_type          VARCHAR(64) NOT NULL,
    reason_code         VARCHAR(64) NOT NULL,
    actor_user_id       BIGINT      NULL,
    target_user_id      BIGINT      NULL,
    occurred_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_project_mgmt_attention_audit_project FOREIGN KEY (project_id) REFERENCES public.projects (id) ON DELETE CASCADE
);

CREATE INDEX idx_project_mgmt_attention_audit_project
    ON public.project_management_attention_audit (project_id, occurred_at DESC);

REVOKE ALL ON TABLE public.project_management_attention_audit FROM PUBLIC;
ALTER TABLE public.project_management_attention_audit ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_project_management_attention_audit
    ON public.project_management_attention_audit
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);

CREATE TABLE public.user_project_reconciliation_state (
    user_id                       BIGINT      PRIMARY KEY,
    last_reconciled_token_version BIGINT      NOT NULL,
    last_reconciled_lifecycle     VARCHAR(32) NOT NULL,
    last_event_id                 UUID        NOT NULL,
    reconciled_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_user_proj_reconcile_user FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

REVOKE ALL ON TABLE public.user_project_reconciliation_state FROM PUBLIC;
ALTER TABLE public.user_project_reconciliation_state ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_user_project_reconciliation_state
    ON public.user_project_reconciliation_state
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);

CREATE TABLE public.project_invitations (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    project_id          BIGINT       NOT NULL,
    invitee_email       VARCHAR(255) NOT NULL,
    token_hash          VARCHAR(64)  NOT NULL,
    invited_by_user_id  BIGINT       NOT NULL,
    issued_at           TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,
    consumed_at         TIMESTAMPTZ  NULL,
    consumed_by_user_id BIGINT       NULL,
    revoked_at          TIMESTAMPTZ  NULL,
    revoked_by_user_id  BIGINT       NULL,
    revocation_reason   VARCHAR(64)  NULL,
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT fk_project_invitations_project FOREIGN KEY (project_id) REFERENCES public.projects (id) ON DELETE CASCADE,
    CONSTRAINT chk_project_invitations_version_non_negative CHECK (version >= 0)
);

CREATE UNIQUE INDEX ux_project_invitations_token_hash
    ON public.project_invitations (token_hash);

CREATE INDEX idx_project_invitations_project_email
    ON public.project_invitations (project_id, invitee_email);

REVOKE ALL ON TABLE public.project_invitations FROM PUBLIC;
ALTER TABLE public.project_invitations ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_project_invitations
    ON public.project_invitations
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);
