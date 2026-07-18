package com.taskpilot.ai.tools;

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

    public static String userInput() {
        Context ctx = get();
        return ctx == null ? "" : ctx.userInput();
    }

    public record Context(Long userId, Long sessionId, String userInput, java.util.Collection<String> allowedTools) {
        public Context(Long userId, Long sessionId, String userInput) {
            this(userId, sessionId, userInput, java.util.Collections.emptySet());
        }
    }
}
