package com.taskpilot.ai.tools;

import com.taskpilot.contracts.user.dto.CurrentActor;

public final class ToolExecutionContext {

    public static final ScopedValue<Context> HOLDER = ScopedValue.newInstance();
    private static final ThreadLocal<Context> THREAD_LOCAL_HOLDER = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static void set(Context context) {
        THREAD_LOCAL_HOLDER.set(context);
    }

    public static void clear() {
        THREAD_LOCAL_HOLDER.remove();
    }

    public static Context get() {
        if (HOLDER.isBound()) {
            return HOLDER.get();
        }
        return THREAD_LOCAL_HOLDER.get();
    }

    public static <T, X extends Throwable> T callWith(Context context, ScopedValue.CallableOp<T, X> action) throws X {
        return ScopedValue.where(HOLDER, context).call(action);
    }

    public static void runWith(Context context, Runnable action) {
        ScopedValue.where(HOLDER, context).run(action);
    }

    public static Long requireUserId() {
        Context ctx = get();
        if (ctx == null || ctx.userId() == null) {
            throw new IllegalStateException("Tool context missing userId");
        }
        return ctx.userId();
    }

    public static Long requireSessionId() {
        Context ctx = get();
        if (ctx == null || ctx.sessionId() == null) {
            throw new IllegalStateException("Tool context missing sessionId");
        }
        return ctx.sessionId();
    }

    public static CurrentActor requireActor() {
        Context ctx = get();
        if (ctx == null || ctx.userId() == null) {
            throw new IllegalStateException("Tool context missing userId");
        }
        if (ctx.actor() != null) {
            return ctx.actor();
        }
        return new CurrentActor(ctx.userId(), null, null, 0L);
    }

    public static String userInput() {
        Context ctx = get();
        return ctx == null ? "" : ctx.userInput();
    }

    public record Context(Long userId, Long sessionId, String userInput, java.util.Collection<String> allowedTools, CurrentActor actor) {
        public Context(Long userId, Long sessionId, String userInput, java.util.Collection<String> allowedTools, CurrentActor actor) {
            // Flexible Constructor Body: pre-assignment validation and sanitization
            if (userInput != null && userInput.length() > 10000) {
                userInput = userInput.substring(0, 10000);
            }
            this.userId = userId;
            this.sessionId = sessionId;
            this.userInput = userInput;
            this.allowedTools = allowedTools != null ? allowedTools : java.util.Collections.emptySet();
            this.actor = actor;
        }

        public Context(Long userId, Long sessionId, String userInput, java.util.Collection<String> allowedTools) {
            this(userId, sessionId, userInput, allowedTools, null);
        }

        public Context(Long userId, Long sessionId, String userInput) {
            this(userId, sessionId, userInput, java.util.Collections.emptySet(), null);
        }
    }
}
