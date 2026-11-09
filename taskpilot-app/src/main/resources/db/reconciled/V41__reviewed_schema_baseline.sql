-- Reviewed public-schema baseline after the V41 incident.
-- Structure only; legacy history, ownership, ACL and user data are excluded.
-- Existing databases are adopted explicitly at 41: this script is never replayed there.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;
CREATE TYPE public.chat_sender AS ENUM (
    'USER',
    'ASSISTANT',
    'SYSTEM'
);
CREATE TYPE public.heuristic_mode AS ENUM (
    'BALANCED',
    'URGENT',
    'TRAINING'
);
CREATE TYPE public.heuristicmode AS ENUM (
    'BALANCED',
    'TRAINING',
    'URGENT'
);
CREATE TYPE public.memberrole AS ENUM (
    'MANAGER',
    'MEMBER'
);
CREATE TYPE public.notification_type AS ENUM (
    'SYSTEM',
    'ASSIGNED',
    'DEADLINE_NEAR',
    'COMMENT',
    'MENTION',
    'REPLY'
);
CREATE TYPE public.notificationtype AS ENUM (
    'ASSIGNED',
    'COMMENT',
    'DEADLINE_NEAR',
    'MENTION',
    'REPLY',
    'SYSTEM'
);
CREATE TYPE public.priority_level AS ENUM (
    'LOW',
    'MEDIUM',
    'HIGH',
    'URGENT'
);
CREATE TYPE public.prioritylevel AS ENUM (
    'HIGH',
    'LOW',
    'MEDIUM',
    'URGENT'
);
CREATE TYPE public.project_role AS ENUM (
    'MANAGER',
    'MEMBER'
);
CREATE TYPE public.project_status AS ENUM (
    'PLANNING',
    'ACTIVE',
    'COMPLETED',
    'ARCHIVED'
);
CREATE TYPE public.projectstatus AS ENUM (
    'ACTIVE',
    'ARCHIVED',
    'COMPLETED',
    'PLANNING'
);
CREATE TYPE public.sendertype AS ENUM (
    'ASSISTANT',
    'SYSTEM',
    'USER'
);
CREATE TYPE public.sprint_status AS ENUM (
    'PLANNING',
    'ACTIVE',
    'COMPLETED'
);
CREATE TYPE public.sprintstatus AS ENUM (
    'ACTIVE',
    'COMPLETED',
    'PLANNING'
);
CREATE TYPE public.system_role AS ENUM (
    'ADMIN',
    'USER'
);
CREATE TYPE public.task_status AS ENUM (
    'TODO',
    'IN_PROGRESS',
    'REVIEW',
    'DONE'
);
CREATE TYPE public.taskstatus AS ENUM (
    'DONE',
    'IN_PROGRESS',
    'REVIEW',
    'TODO'
);
CREATE TYPE public.user_status AS ENUM (
    'AVAILABLE',
    'BUSY',
    'OOO',
    'DEACTIVATED'
);
CREATE TYPE public.userrole AS ENUM (
    'ADMIN',
    'USER'
);
CREATE TYPE public.userstatus AS ENUM (
    'AVAILABLE',
    'BUSY',
    'DEACTIVATED',
    'OOO'
);
CREATE TABLE public.adaptive_weight_proposals (
    proposal_id character varying(64) NOT NULL,
    status character varying(32) NOT NULL,
    dataset_hash character varying(64) NOT NULL,
    dataset_sample_count integer NOT NULL,
    current_fit_weight double precision NOT NULL,
    current_load_weight double precision NOT NULL,
    current_performance_weight double precision NOT NULL,
    proposed_fit_weight double precision NOT NULL,
    proposed_load_weight double precision NOT NULL,
    proposed_performance_weight double precision NOT NULL,
    grid_step double precision NOT NULL,
    metric_historical_agreement double precision NOT NULL,
    metric_on_time_association double precision NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by_user_id bigint NOT NULL,
    reviewed_at timestamp with time zone,
    reviewed_by_user_id bigint,
    review_notes character varying(1000),
    rejection_reason character varying(1000),
    proposal_version character varying(32) DEFAULT 'proposal-v1'::character varying NOT NULL
);
CREATE TABLE public.ai_chat_memories (
    session_id bigint NOT NULL,
    messages_json text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE public.ai_chat_memories IS 'Persistent LangChain4j chat memory snapshots by session';
COMMENT ON COLUMN public.ai_chat_memories.messages_json IS 'Serialized List<ChatMessage> in JSON format';
CREATE TABLE public.ai_chat_requests (
    id bigint NOT NULL,
    session_id bigint NOT NULL,
    user_id bigint NOT NULL,
    client_message_id character varying(128) NOT NULL,
    phase character varying(32) NOT NULL,
    model_used character varying(100),
    assistant_message_id bigint,
    error_message text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE public.ai_chat_requests IS 'Tracks per-message AI stream processing phases for polling UX';
COMMENT ON COLUMN public.ai_chat_requests.phase IS 'QUEUED, ROUTING, THINKING, GENERATING, FINALIZED, FAILED';
CREATE SEQUENCE public.ai_chat_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.ai_chat_requests_id_seq OWNED BY public.ai_chat_requests.id;
CREATE TABLE public.ai_logs (
    id bigint NOT NULL,
    project_id bigint,
    user_id bigint,
    chat_message_id bigint,
    request text,
    response text,
    reasoning text,
    action_taken character varying(255),
    tool_output jsonb,
    human_feedback character varying(255) DEFAULT 'PENDING'::character varying,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    model_used character varying(255),
    tokens_used integer,
    duration_ms integer,
    session_id bigint
);
COMMENT ON COLUMN public.ai_logs.request IS 'Câu hỏi/Prompt gốc của User';
COMMENT ON COLUMN public.ai_logs.response IS 'Câu trả lời cuối cùng của AI';
COMMENT ON COLUMN public.ai_logs.reasoning IS 'Chain of Thought (CoT) - Luồng suy nghĩ của AI';
COMMENT ON COLUMN public.ai_logs.action_taken IS 'Function Calling Name (Tên hàm AI đã gọi)';
COMMENT ON COLUMN public.ai_logs.tool_output IS 'Kết quả trả về từ Tool/Heuristic (Vd: {"score": 9.5})';
COMMENT ON COLUMN public.ai_logs.human_feedback IS 'Trạng thái đánh giá: PENDING, ACCEPTED, REJECTED';
COMMENT ON COLUMN public.ai_logs.model_used IS 'LangChain4j model bean used: gemini-2.5-flash, gpt-4o, DeepSeek-R1';
COMMENT ON COLUMN public.ai_logs.tokens_used IS 'Estimated tokens consumed by this AI interaction';
COMMENT ON COLUMN public.ai_logs.duration_ms IS 'Wall-clock time in milliseconds from request to first/final token';
COMMENT ON COLUMN public.ai_logs.session_id IS 'FK to chat_sessions - links log to a specific chat session';
CREATE SEQUENCE public.ai_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.ai_logs_id_seq OWNED BY public.ai_logs.id;
CREATE TABLE public.chat_messages (
    id bigint NOT NULL,
    session_id bigint,
    sender public.chat_sender NOT NULL,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    client_message_id character varying(255)
);
COMMENT ON COLUMN public.chat_messages.client_message_id IS 'Client-side idempotency key to deduplicate retried SSE chat requests';
CREATE SEQUENCE public.chat_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.chat_messages_id_seq OWNED BY public.chat_messages.id;
CREATE TABLE public.chat_sessions (
    id bigint NOT NULL,
    user_id bigint,
    title character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN public.chat_sessions.updated_at IS 'Last activity time in this session (updated on each new message)';
CREATE SEQUENCE public.chat_sessions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.chat_sessions_id_seq OWNED BY public.chat_sessions.id;
CREATE TABLE public.comment_mentions (
    comment_id bigint NOT NULL,
    user_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE public.comments (
    id bigint NOT NULL,
    task_id bigint,
    user_id bigint,
    content text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    parent_comment_id bigint,
    deleted_at timestamp with time zone,
    deleted_by bigint
);
CREATE SEQUENCE public.comments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.comments_id_seq OWNED BY public.comments.id;
CREATE TABLE public.document_chunk_staging (
    id bigint NOT NULL,
    document_id bigint NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    embedding public.vector(768),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.document_chunk_staging ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_chunk_staging_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.document_chunks (
    id bigint NOT NULL,
    document_id bigint NOT NULL,
    project_id bigint NOT NULL,
    chunk_index integer NOT NULL,
    content text NOT NULL,
    embedding public.vector(768) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.document_chunks ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.document_chunks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.documents (
    id bigint NOT NULL,
    project_id bigint NOT NULL,
    storage_key character varying(512) NOT NULL,
    original_filename character varying(255) NOT NULL,
    content_type character varying(100),
    file_size bigint,
    status character varying(32) DEFAULT 'UPLOADING'::character varying NOT NULL,
    error_message text,
    created_by bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    processing_version integer DEFAULT 0 NOT NULL,
    lease_until timestamp with time zone,
    retry_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone
);
ALTER TABLE public.documents ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.documents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.labels (
    id bigint NOT NULL,
    project_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    color character varying(7) DEFAULT '#6366F1'::character varying,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE public.labels IS 'Project-scoped labels. Created inline by project members.';
CREATE SEQUENCE public.labels_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.labels_id_seq OWNED BY public.labels.id;
CREATE TABLE public.notifications (
    id bigint NOT NULL,
    user_id bigint,
    title character varying(255) NOT NULL,
    message text,
    type public.notification_type,
    is_read boolean DEFAULT false,
    link_action character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
CREATE SEQUENCE public.notifications_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.notifications_id_seq OWNED BY public.notifications.id;
CREATE TABLE public.password_reset_tokens (
    id bigint NOT NULL,
    token character varying(255) NOT NULL,
    expiry_date timestamp with time zone NOT NULL,
    is_used boolean DEFAULT false NOT NULL,
    user_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone
);
CREATE SEQUENCE public.password_reset_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.password_reset_tokens_id_seq OWNED BY public.password_reset_tokens.id;
CREATE TABLE public.project_chat_messages (
    id bigint NOT NULL,
    project_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    content text NOT NULL,
    message_type character varying(255) DEFAULT 'TEXT'::character varying NOT NULL,
    file_id bigint,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.project_chat_messages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_chat_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.project_files (
    id bigint NOT NULL,
    project_id bigint NOT NULL,
    uploader_id bigint,
    file_name character varying(255) NOT NULL,
    original_name character varying(255) NOT NULL,
    file_size bigint NOT NULL,
    content_type character varying(255),
    storage_key character varying(512) NOT NULL,
    storage_bucket character varying(255) DEFAULT 'documents'::character varying NOT NULL,
    description text,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.project_files ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_files_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.project_meeting_participants (
    id bigint NOT NULL,
    meeting_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(32) DEFAULT 'PARTICIPANT'::character varying NOT NULL,
    joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    left_at timestamp with time zone
);
ALTER TABLE public.project_meeting_participants ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_meeting_participants_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.project_meetings (
    id bigint NOT NULL,
    project_id bigint NOT NULL,
    host_id bigint,
    title character varying(255) NOT NULL,
    description text,
    room_name character varying(255) NOT NULL,
    status character varying(32) DEFAULT 'ACTIVE'::character varying NOT NULL,
    recording_enabled boolean DEFAULT false NOT NULL,
    recording_file_id bigint,
    started_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    ended_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    scheduled_end_time timestamp(6) with time zone,
    scheduled_start_time timestamp(6) with time zone,
    notes text,
    transcript text,
    ai_summary text,
    ai_summary_generated_at timestamp with time zone,
    action_items_json text
);
ALTER TABLE public.project_meetings ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.project_meetings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.project_members (
    project_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role public.project_role DEFAULT 'MEMBER'::public.project_role,
    joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    performance_score double precision DEFAULT 0.5
);
CREATE TABLE public.projects (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description text,
    status public.project_status DEFAULT 'ACTIVE'::public.project_status,
    start_date date,
    end_date date,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    heuristic_mode public.heuristic_mode DEFAULT 'BALANCED'::public.heuristic_mode,
    workflow_mode character varying(255) DEFAULT 'KANBAN'::character varying NOT NULL
);
CREATE SEQUENCE public.projects_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.projects_id_seq OWNED BY public.projects.id;
CREATE TABLE public.recommendation_decision_events (
    decision_id character varying(64) NOT NULL,
    snapshot_id character varying(64) NOT NULL,
    project_id bigint NOT NULL,
    task_id bigint,
    decided_by_user_id bigint,
    decision_source character varying(16) NOT NULL,
    decision_type character varying(32) NOT NULL,
    recommended_candidate_id bigint,
    selected_candidate_id bigint,
    reason_code character varying(64),
    note character varying(1000),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE public.recommendation_snapshot_candidates (
    id bigint NOT NULL,
    snapshot_id character varying(64) NOT NULL,
    candidate_id bigint NOT NULL,
    rank integer NOT NULL,
    ranking_key bigint NOT NULL,
    full_precision_score double precision NOT NULL,
    ranking_raw_fit double precision NOT NULL,
    presentation_fit_value double precision,
    fit_status character varying(32) NOT NULL,
    stored_workload_value integer,
    workload_status character varying(32) NOT NULL,
    derived_performance_input double precision NOT NULL,
    performance_status character varying(32) NOT NULL,
    member_status character varying(32) NOT NULL,
    selected_as_recommendation boolean NOT NULL,
    workload_unit character varying(32),
    workload_scope character varying(32),
    workload_measured_at timestamp with time zone
);
ALTER TABLE public.recommendation_snapshot_candidates ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.recommendation_snapshot_candidates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.recommendation_snapshots (
    snapshot_id character varying(64) NOT NULL,
    requested_by_user_id bigint NOT NULL,
    project_id bigint NOT NULL,
    task_id bigint,
    created_at timestamp with time zone NOT NULL,
    heuristic_mode character varying(32) NOT NULL,
    presentation_contract_version character varying(64) NOT NULL,
    scoring_model_version character varying(64) NOT NULL,
    differentiation_status character varying(64) NOT NULL,
    required_skills text NOT NULL,
    recommended_candidate_id bigint,
    candidate_count integer NOT NULL,
    request_source character varying(64) NOT NULL,
    fit_weight double precision NOT NULL,
    load_weight double precision NOT NULL,
    performance_weight double precision NOT NULL,
    normalization_contract character varying(64) NOT NULL
);
CREATE TABLE public.recommendation_task_outcomes (
    outcome_id character varying(64) NOT NULL,
    snapshot_id character varying(64) NOT NULL,
    decision_id character varying(64) NOT NULL,
    project_id bigint NOT NULL,
    task_id bigint NOT NULL,
    recommended_candidate_id bigint,
    selected_candidate_id bigint,
    decision_type character varying(32) NOT NULL,
    decision_source character varying(16) NOT NULL,
    observed_assignee_id bigint,
    task_status character varying(32) NOT NULL,
    due_at timestamp with time zone,
    completed_at timestamp with time zone,
    outcome_type character varying(32) NOT NULL,
    exclusion_reason character varying(64),
    observed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    outcome_version character varying(32) DEFAULT 'outcome-v1'::character varying NOT NULL
);
CREATE TABLE public.refresh_tokens (
    id bigint NOT NULL,
    token character varying(255) NOT NULL,
    expiry_date timestamp with time zone NOT NULL,
    user_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone
);
CREATE SEQUENCE public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.refresh_tokens_id_seq OWNED BY public.refresh_tokens.id;
CREATE TABLE public.skills (
    id bigint NOT NULL,
    name character varying(255) NOT NULL,
    description character varying(255),
    is_active boolean DEFAULT true
);
CREATE SEQUENCE public.skills_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.skills_id_seq OWNED BY public.skills.id;
CREATE TABLE public.sprints (
    id bigint NOT NULL,
    project_id bigint,
    name character varying(255) NOT NULL,
    goal text,
    status public.sprint_status DEFAULT 'PLANNING'::public.sprint_status,
    start_date date,
    end_date date,
    heuristic_mode public.heuristic_mode
);
COMMENT ON COLUMN public.sprints.goal IS 'Context cung cấp cho AI để đánh giá tiến độ';
COMMENT ON COLUMN public.sprints.heuristic_mode IS 'Ghi đè chiến lược AI cho riêng Sprint này (Nếu NULL thì lấy của Project)';
CREATE SEQUENCE public.sprints_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.sprints_id_seq OWNED BY public.sprints.id;
CREATE TABLE public.system_settings (
    key_name character varying(255) NOT NULL,
    value_json jsonb NOT NULL,
    description character varying(255)
);
COMMENT ON COLUMN public.system_settings.key_name IS 'Vd: heuristic.weights';
COMMENT ON COLUMN public.system_settings.value_json IS 'Lưu trọng số thuật toán';
CREATE TABLE public.task_labels (
    task_id bigint NOT NULL,
    label_id bigint NOT NULL
);
CREATE TABLE public.task_required_skills (
    task_id bigint NOT NULL,
    skill_id bigint NOT NULL
);
COMMENT ON TABLE public.task_required_skills IS 'Projects module owns task_id + skill_id. Skill data fetched via SkillPort.';
CREATE TABLE public.tasks (
    id bigint NOT NULL,
    project_id bigint,
    parent_id bigint,
    sprint_id bigint,
    title character varying(255) NOT NULL,
    description text,
    status public.task_status DEFAULT 'TODO'::public.task_status,
    priority public.priority_level DEFAULT 'MEDIUM'::public.priority_level,
    "position" double precision DEFAULT 0,
    tags jsonb,
    difficulty_level integer DEFAULT 1,
    required_skills jsonb,
    assignee_id bigint,
    reporter_id bigint,
    start_date timestamp with time zone,
    due_date timestamp with time zone,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamp with time zone
);
COMMENT ON COLUMN public.tasks.parent_id IS 'Subtask Logic (Dùng cho Use-case 47)';
COMMENT ON COLUMN public.tasks."position" IS 'Dùng để sort Kanban Board (Kéo thả)';
COMMENT ON COLUMN public.tasks.tags IS 'JSONB Array: vd ["Bug", "Urgent"]';
COMMENT ON COLUMN public.tasks.difficulty_level IS 'Heuristic Input: Thang điểm 1-10';
COMMENT ON COLUMN public.tasks.required_skills IS 'AI Matching: vd ["Java", "AWS"]';
CREATE SEQUENCE public.tasks_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.tasks_id_seq OWNED BY public.tasks.id;
CREATE TABLE public.team_chat_message_reactions (
    id bigint NOT NULL,
    room_id bigint NOT NULL,
    message_id bigint NOT NULL,
    user_id bigint NOT NULL,
    emoji character varying(32) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.team_chat_message_reactions ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_chat_message_reactions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.team_chat_messages (
    id bigint NOT NULL,
    room_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    content text NOT NULL,
    message_type character varying(30) DEFAULT 'TEXT'::character varying NOT NULL,
    reply_to_id bigint,
    is_deleted boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.team_chat_messages ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_chat_messages_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.team_chat_room_members (
    id bigint NOT NULL,
    room_id bigint NOT NULL,
    user_id bigint NOT NULL,
    role character varying(30) DEFAULT 'MEMBER'::character varying NOT NULL,
    last_read_message_id bigint,
    last_read_at timestamp with time zone,
    joined_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.team_chat_room_members ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_chat_room_members_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.team_chat_rooms (
    id bigint NOT NULL,
    project_id bigint,
    name character varying(100) NOT NULL,
    type character varying(30) DEFAULT 'PROJECT_CHANNEL'::character varying NOT NULL,
    is_private boolean DEFAULT false NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.team_chat_rooms ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.team_chat_rooms_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.user_identities (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    provider character varying(32) NOT NULL,
    provider_subject character varying(255) NOT NULL,
    email character varying(255),
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    last_login_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE public.user_identities ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.user_identities_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);
CREATE TABLE public.user_skills (
    user_id bigint NOT NULL,
    skill_id bigint NOT NULL,
    level integer
);
COMMENT ON COLUMN public.user_skills.level IS 'Scale 1-5';
CREATE TABLE public.users (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    full_name character varying(255) NOT NULL,
    password_hash character varying(255),
    avatar_url text,
    role public.system_role DEFAULT 'USER'::public.system_role,
    status public.user_status DEFAULT 'AVAILABLE'::public.user_status,
    current_workload integer DEFAULT 0,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON COLUMN public.users.current_workload IS 'Tổng độ khó task đang làm';
CREATE SEQUENCE public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;
ALTER SEQUENCE public.users_id_seq OWNED BY public.users.id;
ALTER TABLE ONLY public.ai_chat_requests ALTER COLUMN id SET DEFAULT nextval('public.ai_chat_requests_id_seq'::regclass);
ALTER TABLE ONLY public.ai_logs ALTER COLUMN id SET DEFAULT nextval('public.ai_logs_id_seq'::regclass);
ALTER TABLE ONLY public.chat_messages ALTER COLUMN id SET DEFAULT nextval('public.chat_messages_id_seq'::regclass);
ALTER TABLE ONLY public.chat_sessions ALTER COLUMN id SET DEFAULT nextval('public.chat_sessions_id_seq'::regclass);
ALTER TABLE ONLY public.comments ALTER COLUMN id SET DEFAULT nextval('public.comments_id_seq'::regclass);
ALTER TABLE ONLY public.labels ALTER COLUMN id SET DEFAULT nextval('public.labels_id_seq'::regclass);
ALTER TABLE ONLY public.notifications ALTER COLUMN id SET DEFAULT nextval('public.notifications_id_seq'::regclass);
ALTER TABLE ONLY public.password_reset_tokens ALTER COLUMN id SET DEFAULT nextval('public.password_reset_tokens_id_seq'::regclass);
ALTER TABLE ONLY public.projects ALTER COLUMN id SET DEFAULT nextval('public.projects_id_seq'::regclass);
ALTER TABLE ONLY public.refresh_tokens ALTER COLUMN id SET DEFAULT nextval('public.refresh_tokens_id_seq'::regclass);
ALTER TABLE ONLY public.skills ALTER COLUMN id SET DEFAULT nextval('public.skills_id_seq'::regclass);
ALTER TABLE ONLY public.sprints ALTER COLUMN id SET DEFAULT nextval('public.sprints_id_seq'::regclass);
ALTER TABLE ONLY public.tasks ALTER COLUMN id SET DEFAULT nextval('public.tasks_id_seq'::regclass);
ALTER TABLE ONLY public.users ALTER COLUMN id SET DEFAULT nextval('public.users_id_seq'::regclass);
ALTER TABLE ONLY public.adaptive_weight_proposals
    ADD CONSTRAINT adaptive_weight_proposals_pkey PRIMARY KEY (proposal_id);
ALTER TABLE ONLY public.ai_chat_memories
    ADD CONSTRAINT ai_chat_memories_pkey PRIMARY KEY (session_id);
ALTER TABLE ONLY public.ai_chat_requests
    ADD CONSTRAINT ai_chat_requests_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.ai_logs
    ADD CONSTRAINT ai_logs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.comment_mentions
    ADD CONSTRAINT comment_mentions_pkey PRIMARY KEY (comment_id, user_id);
ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.document_chunk_staging
    ADD CONSTRAINT document_chunk_staging_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT document_chunks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.documents
    ADD CONSTRAINT documents_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.labels
    ADD CONSTRAINT labels_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT password_reset_tokens_token_key UNIQUE (token);
ALTER TABLE ONLY public.project_chat_messages
    ADD CONSTRAINT project_chat_messages_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.project_files
    ADD CONSTRAINT project_files_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.project_meeting_participants
    ADD CONSTRAINT project_meeting_participants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.project_meetings
    ADD CONSTRAINT project_meetings_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.project_meetings
    ADD CONSTRAINT project_meetings_room_name_key UNIQUE (room_name);
ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_pkey PRIMARY KEY (project_id, user_id);
ALTER TABLE ONLY public.projects
    ADD CONSTRAINT projects_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.recommendation_decision_events
    ADD CONSTRAINT recommendation_decision_events_pkey PRIMARY KEY (decision_id);
ALTER TABLE ONLY public.recommendation_decision_events
    ADD CONSTRAINT recommendation_decision_events_snapshot_id_key UNIQUE (snapshot_id);
ALTER TABLE ONLY public.recommendation_snapshot_candidates
    ADD CONSTRAINT recommendation_snapshot_candidates_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.recommendation_snapshots
    ADD CONSTRAINT recommendation_snapshots_pkey PRIMARY KEY (snapshot_id);
ALTER TABLE ONLY public.recommendation_task_outcomes
    ADD CONSTRAINT recommendation_task_outcomes_pkey PRIMARY KEY (outcome_id);
ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT refresh_tokens_token_key UNIQUE (token);
ALTER TABLE ONLY public.skills
    ADD CONSTRAINT skills_name_key UNIQUE (name);
ALTER TABLE ONLY public.skills
    ADD CONSTRAINT skills_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.sprints
    ADD CONSTRAINT sprints_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.system_settings
    ADD CONSTRAINT system_settings_pkey PRIMARY KEY (key_name);
ALTER TABLE ONLY public.task_labels
    ADD CONSTRAINT task_labels_pkey PRIMARY KEY (task_id, label_id);
ALTER TABLE ONLY public.task_required_skills
    ADD CONSTRAINT task_required_skills_pkey PRIMARY KEY (task_id, skill_id);
ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.team_chat_message_reactions
    ADD CONSTRAINT team_chat_message_reactions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.team_chat_messages
    ADD CONSTRAINT team_chat_messages_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.team_chat_room_members
    ADD CONSTRAINT team_chat_room_members_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.team_chat_rooms
    ADD CONSTRAINT team_chat_rooms_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.recommendation_task_outcomes
    ADD CONSTRAINT uk_task_outcomes_decision_task UNIQUE (decision_id, task_id);
ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT uk_user_identities_provider_subject UNIQUE (provider, provider_subject);
ALTER TABLE ONLY public.ai_chat_requests
    ADD CONSTRAINT uq_ai_chat_requests_session_client UNIQUE (session_id, client_message_id);
ALTER TABLE ONLY public.team_chat_message_reactions
    ADD CONSTRAINT uq_chat_message_reaction_user UNIQUE (message_id, user_id, emoji);
ALTER TABLE ONLY public.recommendation_snapshot_candidates
    ADD CONSTRAINT uq_snapshot_candidates_snapshot_rank UNIQUE (snapshot_id, rank);
ALTER TABLE ONLY public.document_chunk_staging
    ADD CONSTRAINT uq_staging_doc_chunk UNIQUE (document_id, chunk_index);
ALTER TABLE ONLY public.team_chat_room_members
    ADD CONSTRAINT uq_team_chat_room_member UNIQUE (room_id, user_id);
ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT user_identities_pkey PRIMARY KEY (id);
ALTER TABLE ONLY public.user_skills
    ADD CONSTRAINT user_skills_pkey PRIMARY KEY (user_id, skill_id);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);
ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);
CREATE INDEX idx_adaptive_proposals_created_at ON public.adaptive_weight_proposals USING btree (created_at);
CREATE INDEX idx_adaptive_proposals_status ON public.adaptive_weight_proposals USING btree (status);
CREATE INDEX idx_ai_chat_memories_updated ON public.ai_chat_memories USING btree (updated_at DESC);
CREATE INDEX idx_ai_chat_requests_session_updated ON public.ai_chat_requests USING btree (session_id, updated_at DESC);
CREATE INDEX idx_ai_chat_requests_user_updated ON public.ai_chat_requests USING btree (user_id, updated_at DESC);
CREATE INDEX idx_ai_logs_session_id ON public.ai_logs USING btree (session_id);
CREATE INDEX idx_ai_logs_user_created ON public.ai_logs USING btree (user_id, created_at DESC);
CREATE INDEX idx_chat_messages_session_created ON public.chat_messages USING btree (session_id, created_at);
CREATE INDEX idx_chat_messages_session_sender_client_id ON public.chat_messages USING btree (session_id, sender, client_message_id);
CREATE INDEX idx_chat_reactions_msg_id ON public.team_chat_message_reactions USING btree (message_id);
CREATE INDEX idx_chat_reactions_room_id ON public.team_chat_message_reactions USING btree (room_id);
CREATE INDEX idx_chat_sessions_user_id ON public.chat_sessions USING btree (user_id, updated_at DESC);
CREATE INDEX idx_comment_mentions_user_id ON public.comment_mentions USING btree (user_id);
CREATE INDEX idx_comments_task_created_at ON public.comments USING btree (task_id, created_at);
CREATE INDEX idx_comments_task_parent_created_at ON public.comments USING btree (task_id, parent_comment_id, created_at);
CREATE INDEX idx_comments_user_id ON public.comments USING btree (user_id);
CREATE INDEX idx_document_chunks_document_id ON public.document_chunks USING btree (document_id);
CREATE INDEX idx_document_chunks_embedding_hnsw ON public.document_chunks USING hnsw (embedding public.vector_cosine_ops) WITH (m='16', ef_construction='64');
CREATE INDEX idx_document_chunks_project_id ON public.document_chunks USING btree (project_id);
CREATE INDEX idx_documents_project_id ON public.documents USING btree (project_id);
CREATE INDEX idx_documents_queue_claim ON public.documents USING btree (status, next_attempt_at, lease_until, created_at) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RETRY_WAIT'::character varying, 'PROCESSING'::character varying])::text[]));
CREATE INDEX idx_documents_status ON public.documents USING btree (status);
CREATE INDEX idx_project_chat_messages_created_at ON public.project_chat_messages USING btree (created_at);
CREATE INDEX idx_project_chat_messages_project_id ON public.project_chat_messages USING btree (project_id);
CREATE INDEX idx_project_files_project_id ON public.project_files USING btree (project_id);
CREATE INDEX idx_project_files_uploader_id ON public.project_files USING btree (uploader_id);
CREATE INDEX idx_project_meeting_participants_meeting_id ON public.project_meeting_participants USING btree (meeting_id);
CREATE INDEX idx_project_meeting_participants_user_id ON public.project_meeting_participants USING btree (user_id);
CREATE INDEX idx_project_meetings_ai_summary_generated ON public.project_meetings USING btree (ai_summary_generated_at);
CREATE INDEX idx_project_meetings_project_id ON public.project_meetings USING btree (project_id);
CREATE INDEX idx_project_meetings_room_name ON public.project_meetings USING btree (room_name);
CREATE INDEX idx_project_meetings_status ON public.project_meetings USING btree (status);
CREATE INDEX idx_recommendation_decision_events_decided_by ON public.recommendation_decision_events USING btree (decided_by_user_id);
CREATE INDEX idx_recommendation_decision_events_project_created ON public.recommendation_decision_events USING btree (project_id, created_at);
CREATE INDEX idx_recommendation_snapshot_candidates_snapshot_id ON public.recommendation_snapshot_candidates USING btree (snapshot_id);
CREATE INDEX idx_recommendation_snapshots_project_created ON public.recommendation_snapshots USING btree (project_id, created_at);
CREATE INDEX idx_sprints_project_status ON public.sprints USING btree (project_id, status);
CREATE INDEX idx_staging_doc_id ON public.document_chunk_staging USING btree (document_id);
CREATE INDEX idx_staging_unembedded ON public.document_chunk_staging USING btree (document_id, chunk_index) WHERE (embedding IS NULL);
CREATE INDEX idx_task_outcomes_decision_id ON public.recommendation_task_outcomes USING btree (decision_id);
CREATE INDEX idx_task_outcomes_project_observed ON public.recommendation_task_outcomes USING btree (project_id, observed_at);
CREATE INDEX idx_task_outcomes_task_id ON public.recommendation_task_outcomes USING btree (task_id);
CREATE INDEX idx_tasks_completed_at ON public.tasks USING btree (completed_at);
CREATE INDEX idx_tasks_project ON public.tasks USING btree (project_id);
CREATE INDEX idx_tasks_project_assignee_status ON public.tasks USING btree (project_id, assignee_id, status);
CREATE INDEX idx_tasks_sprint ON public.tasks USING btree (sprint_id);
CREATE INDEX idx_team_chat_messages_room_id_id ON public.team_chat_messages USING btree (room_id, id DESC);
CREATE INDEX idx_team_chat_room_members_user ON public.team_chat_room_members USING btree (user_id);
CREATE INDEX idx_team_chat_rooms_project ON public.team_chat_rooms USING btree (project_id);
CREATE INDEX idx_user_identities_provider_email ON public.user_identities USING btree (provider, email);
CREATE INDEX idx_user_identities_user_id ON public.user_identities USING btree (user_id);
CREATE UNIQUE INDEX idx_users_email_lower_trimmed ON public.users USING btree (lower(TRIM(BOTH FROM email)));
CREATE UNIQUE INDEX uq_chat_messages_user_client_id ON public.chat_messages USING btree (session_id, client_message_id) WHERE ((sender = 'USER'::public.chat_sender) AND (client_message_id IS NOT NULL));
CREATE UNIQUE INDEX uq_label_project_name ON public.labels USING btree (project_id, lower((name)::text));
ALTER TABLE ONLY public.ai_logs
    ADD CONSTRAINT ai_logs_chat_message_id_fkey FOREIGN KEY (chat_message_id) REFERENCES public.chat_messages(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.ai_logs
    ADD CONSTRAINT ai_logs_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.ai_logs
    ADD CONSTRAINT ai_logs_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.chat_sessions(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.ai_logs
    ADD CONSTRAINT ai_logs_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.chat_messages
    ADD CONSTRAINT chat_messages_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.chat_sessions(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.chat_sessions
    ADD CONSTRAINT chat_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.comment_mentions
    ADD CONSTRAINT comment_mentions_comment_id_fkey FOREIGN KEY (comment_id) REFERENCES public.comments(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.comment_mentions
    ADD CONSTRAINT comment_mentions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_task_id_fkey FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.comments
    ADD CONSTRAINT comments_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.ai_chat_memories
    ADD CONSTRAINT fk_ai_chat_memories_session FOREIGN KEY (session_id) REFERENCES public.chat_sessions(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.ai_chat_requests
    ADD CONSTRAINT fk_ai_chat_requests_assistant_message FOREIGN KEY (assistant_message_id) REFERENCES public.chat_messages(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.ai_chat_requests
    ADD CONSTRAINT fk_ai_chat_requests_session FOREIGN KEY (session_id) REFERENCES public.chat_sessions(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.comments
    ADD CONSTRAINT fk_comments_deleted_by FOREIGN KEY (deleted_by) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.comments
    ADD CONSTRAINT fk_comments_parent_comment FOREIGN KEY (parent_comment_id) REFERENCES public.comments(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.recommendation_decision_events
    ADD CONSTRAINT fk_decision_events_snapshot FOREIGN KEY (snapshot_id) REFERENCES public.recommendation_snapshots(snapshot_id);
ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT fk_document_chunks_document FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.document_chunks
    ADD CONSTRAINT fk_document_chunks_project FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.documents
    ADD CONSTRAINT fk_documents_project FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.documents
    ADD CONSTRAINT fk_documents_user FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.labels
    ADD CONSTRAINT fk_label_project FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_chat_messages
    ADD CONSTRAINT fk_project_chat_messages_file FOREIGN KEY (file_id) REFERENCES public.project_files(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.project_chat_messages
    ADD CONSTRAINT fk_project_chat_messages_project FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_chat_messages
    ADD CONSTRAINT fk_project_chat_messages_sender FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_files
    ADD CONSTRAINT fk_project_files_project FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_files
    ADD CONSTRAINT fk_project_files_uploader FOREIGN KEY (uploader_id) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.project_meeting_participants
    ADD CONSTRAINT fk_project_meeting_participants_meeting FOREIGN KEY (meeting_id) REFERENCES public.project_meetings(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_meeting_participants
    ADD CONSTRAINT fk_project_meeting_participants_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_meetings
    ADD CONSTRAINT fk_project_meetings_host FOREIGN KEY (host_id) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.project_meetings
    ADD CONSTRAINT fk_project_meetings_project FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_meetings
    ADD CONSTRAINT fk_project_meetings_recording FOREIGN KEY (recording_file_id) REFERENCES public.project_files(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.recommendation_snapshot_candidates
    ADD CONSTRAINT fk_snapshot_candidates_snapshot FOREIGN KEY (snapshot_id) REFERENCES public.recommendation_snapshots(snapshot_id) ON DELETE CASCADE;
ALTER TABLE ONLY public.document_chunk_staging
    ADD CONSTRAINT fk_staging_document FOREIGN KEY (document_id) REFERENCES public.documents(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.recommendation_task_outcomes
    ADD CONSTRAINT fk_task_outcomes_decision FOREIGN KEY (decision_id) REFERENCES public.recommendation_decision_events(decision_id);
ALTER TABLE ONLY public.recommendation_task_outcomes
    ADD CONSTRAINT fk_task_outcomes_snapshot FOREIGN KEY (snapshot_id) REFERENCES public.recommendation_snapshots(snapshot_id);
ALTER TABLE ONLY public.recommendation_task_outcomes
    ADD CONSTRAINT fk_task_outcomes_task FOREIGN KEY (task_id) REFERENCES public.tasks(id);
ALTER TABLE ONLY public.task_labels
    ADD CONSTRAINT fk_tl_label FOREIGN KEY (label_id) REFERENCES public.labels(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.task_labels
    ADD CONSTRAINT fk_tl_task FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.task_required_skills
    ADD CONSTRAINT fk_trs_task FOREIGN KEY (task_id) REFERENCES public.tasks(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.user_identities
    ADD CONSTRAINT fk_user_identities_user FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.password_reset_tokens
    ADD CONSTRAINT fk_user_password_reset_token FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.refresh_tokens
    ADD CONSTRAINT fk_user_refresh_token FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.notifications
    ADD CONSTRAINT notifications_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.project_members
    ADD CONSTRAINT project_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.sprints
    ADD CONSTRAINT sprints_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_assignee_id_fkey FOREIGN KEY (assignee_id) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES public.tasks(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_reporter_id_fkey FOREIGN KEY (reporter_id) REFERENCES public.users(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.tasks
    ADD CONSTRAINT tasks_sprint_id_fkey FOREIGN KEY (sprint_id) REFERENCES public.sprints(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.team_chat_message_reactions
    ADD CONSTRAINT team_chat_message_reactions_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.team_chat_messages(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_message_reactions
    ADD CONSTRAINT team_chat_message_reactions_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.team_chat_rooms(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_message_reactions
    ADD CONSTRAINT team_chat_message_reactions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_messages
    ADD CONSTRAINT team_chat_messages_reply_to_id_fkey FOREIGN KEY (reply_to_id) REFERENCES public.team_chat_messages(id) ON DELETE SET NULL;
ALTER TABLE ONLY public.team_chat_messages
    ADD CONSTRAINT team_chat_messages_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.team_chat_rooms(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_messages
    ADD CONSTRAINT team_chat_messages_sender_id_fkey FOREIGN KEY (sender_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_room_members
    ADD CONSTRAINT team_chat_room_members_room_id_fkey FOREIGN KEY (room_id) REFERENCES public.team_chat_rooms(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_room_members
    ADD CONSTRAINT team_chat_room_members_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_rooms
    ADD CONSTRAINT team_chat_rooms_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.team_chat_rooms
    ADD CONSTRAINT team_chat_rooms_project_id_fkey FOREIGN KEY (project_id) REFERENCES public.projects(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.user_skills
    ADD CONSTRAINT user_skills_skill_id_fkey FOREIGN KEY (skill_id) REFERENCES public.skills(id) ON DELETE CASCADE;
ALTER TABLE ONLY public.user_skills
    ADD CONSTRAINT user_skills_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;
ALTER TABLE public.ai_chat_memories ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_chat_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.ai_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.chat_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comment_mentions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.comments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.document_chunk_staging ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.document_chunks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY p_ai_logs_deny_client ON public.ai_logs TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_ai_logs_service_role ON public.ai_logs TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_chat_messages_deny_client ON public.chat_messages TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_chat_messages_service_role ON public.chat_messages TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_chat_sessions_deny_client ON public.chat_sessions TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_chat_sessions_service_role ON public.chat_sessions TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_comment_mentions_deny_client ON public.comment_mentions TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_comment_mentions_service_role ON public.comment_mentions TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_comments_deny_client ON public.comments TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_comments_service_role ON public.comments TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_notifications_deny_client ON public.notifications TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_notifications_service_role ON public.notifications TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_password_reset_tokens_deny_client ON public.password_reset_tokens TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_password_reset_tokens_service_role ON public.password_reset_tokens TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_project_members_deny_client ON public.project_members TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_project_members_service_role ON public.project_members TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_projects_deny_client ON public.projects TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_projects_service_role ON public.projects TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_refresh_tokens_deny_client ON public.refresh_tokens TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_refresh_tokens_service_role ON public.refresh_tokens TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_skills_deny_client ON public.skills TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_skills_service_role ON public.skills TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_sprints_deny_client ON public.sprints TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_sprints_service_role ON public.sprints TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_system_settings_deny_client ON public.system_settings TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_system_settings_service_role ON public.system_settings TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_tasks_deny_client ON public.tasks TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_tasks_service_role ON public.tasks TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_team_chat_message_reactions_deny_client ON public.team_chat_message_reactions TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_team_chat_message_reactions_service_role ON public.team_chat_message_reactions TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_team_chat_messages_deny_client ON public.team_chat_messages TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_team_chat_messages_service_role ON public.team_chat_messages TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_team_chat_room_members_deny_client ON public.team_chat_room_members TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_team_chat_room_members_service_role ON public.team_chat_room_members TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_team_chat_rooms_deny_client ON public.team_chat_rooms TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_team_chat_rooms_service_role ON public.team_chat_rooms TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_user_skills_deny_client ON public.user_skills TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_user_skills_service_role ON public.user_skills TO service_role USING (true) WITH CHECK (true);
CREATE POLICY p_users_deny_client ON public.users TO authenticated, anon USING (false) WITH CHECK (false);
CREATE POLICY p_users_service_role ON public.users TO service_role USING (true) WITH CHECK (true);
ALTER TABLE public.password_reset_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.project_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.projects ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sprints ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.system_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.task_required_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.team_chat_message_reactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.team_chat_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.team_chat_room_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.team_chat_rooms ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

-- Reviewed numeric bootstrap configuration; only runs on a fresh database.
INSERT INTO public.system_settings(key_name,value_json,description) VALUES ('heuristic.weights','{"URGENT":{"w_fit":0.47,"w_load":0.06,"w_perf":0.47},"BALANCED":{"w_fit":0.23,"w_load":0.65,"w_perf":0.12},"TRAINING":{"w_fit":0.19,"w_load":0.73,"w_perf":0.08}}'::jsonb,'Reviewed recovery bootstrap heuristic weights') ON CONFLICT(key_name) DO NOTHING;
