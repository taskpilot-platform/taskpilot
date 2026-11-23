ALTER TABLE public.project_meetings
    ADD COLUMN room_cleanup_at TIMESTAMPTZ,
    ADD COLUMN ending_requested_at TIMESTAMPTZ;

ALTER TABLE public.project_meeting_participants
    ADD COLUMN provider_sid VARCHAR(96);

UPDATE public.project_meetings
SET room_cleanup_at = CURRENT_TIMESTAMP
WHERE status = 'ENDED' AND ended_at >= CURRENT_TIMESTAMP - INTERVAL '12 hours 5 minutes';

-- Entries written before provider verification cannot represent a currently connected session.
UPDATE public.project_meeting_participants
SET left_at = GREATEST(joined_at, CURRENT_TIMESTAMP)
WHERE left_at IS NULL;

CREATE INDEX idx_project_meetings_room_cleanup_at
    ON public.project_meetings (room_cleanup_at, id)
    WHERE room_cleanup_at IS NOT NULL;

CREATE UNIQUE INDEX uq_project_meeting_active_participant
    ON public.project_meeting_participants (meeting_id, user_id)
    WHERE left_at IS NULL;

CREATE TABLE public.project_meeting_recordings (
    id UUID PRIMARY KEY,
    meeting_id BIGINT NOT NULL REFERENCES public.project_meetings(id) ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES public.projects(id) ON DELETE CASCADE,
    recorder_id BIGINT REFERENCES public.users(id) ON DELETE SET NULL,
    project_file_id BIGINT UNIQUE REFERENCES public.project_files(id) ON DELETE SET NULL,
    origin VARCHAR(16) NOT NULL,
    coverage VARCHAR(48) NOT NULL,
    state VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    duration_ms BIGINT,
    byte_length BIGINT,
    mime_type VARCHAR(96),
    checksum VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ
);

CREATE INDEX idx_project_meeting_recordings_meeting
    ON public.project_meeting_recordings (meeting_id, created_at DESC);

CREATE INDEX idx_project_meeting_recordings_expiry
    ON public.project_meeting_recordings (expires_at)
    WHERE expires_at IS NOT NULL;
