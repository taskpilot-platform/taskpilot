CREATE TABLE public.oauth2_authorization_requests (
    handle_hash     VARCHAR(64)  PRIMARY KEY,
    nonce_hash      VARCHAR(64)  NOT NULL,
    state_hash      VARCHAR(64)  NOT NULL UNIQUE,
    provider        VARCHAR(32)  NOT NULL,
    purpose         VARCHAR(16)  NOT NULL DEFAULT 'LOGIN',
    target_user_id  BIGINT       NULL,
    redirect_uri    VARCHAR(512) NOT NULL,
    payload_json    TEXT         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    consumed_at     TIMESTAMPTZ  NULL,
    CONSTRAINT chk_oauth2_auth_req_purpose CHECK (purpose IN ('LOGIN', 'LINK')),
    CONSTRAINT chk_oauth2_auth_req_link_user CHECK (
        (purpose = 'LOGIN' AND target_user_id IS NULL)
        OR (purpose = 'LINK' AND target_user_id IS NOT NULL)
    )
);

CREATE INDEX idx_oauth2_auth_req_expires_at
    ON public.oauth2_authorization_requests (expires_at);

CREATE INDEX idx_oauth2_auth_req_consumed_at
    ON public.oauth2_authorization_requests (consumed_at)
    WHERE consumed_at IS NOT NULL;

REVOKE ALL ON TABLE public.oauth2_authorization_requests FROM PUBLIC;
ALTER TABLE public.oauth2_authorization_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY deny_all_oauth2_authorization_requests
    ON public.oauth2_authorization_requests
    FOR ALL
    TO PUBLIC
    USING (false)
    WITH CHECK (false);
