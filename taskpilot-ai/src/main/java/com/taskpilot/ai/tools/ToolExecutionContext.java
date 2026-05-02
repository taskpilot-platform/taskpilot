package com.taskpilot.ai.tools;

public final class ToolExecutionContext {

    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private ToolExecutionContext() {
    }

    public static void set(Context context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Context get() {
        return HOLDER.get();
    }

    public static Long requireUserId() {
        Context ctx = HOLDER.get();
        if (ctx == null || ctx.userId() == null) {
            throw new IllegalStateException("Tool context missing userId");
        }
        return ctx.userId();
    }

    public record Context(Long userId, Long sessionId) {
    }
}
