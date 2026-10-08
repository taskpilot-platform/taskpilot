package com.taskpilot.ai.streaming.testutil;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controllable, deterministic ScheduledExecutorService for tests without Thread.sleep.
 * Supports manual advancing of virtual time and synchronous execution of due tasks.
 */
public class DeterministicTestScheduler implements ScheduledExecutorService {

    private long currentTimeMs = 0;
    private final PriorityQueue<ScheduledTask<?>> taskQueue = new PriorityQueue<>(Comparator
            .comparingLong((ScheduledTask<?> t) -> t.triggerTimeMs)
            .thenComparingLong(t -> t.sequence));
    private final AtomicLong sequenceGenerator = new AtomicLong(0);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public synchronized long getCurrentTimeMs() {
        return currentTimeMs;
    }

    public synchronized int getPendingTaskCount() {
        return (int) taskQueue.stream().filter(t -> !t.isCancelled()).count();
    }

    /**
     * Advances virtual time by the given duration and synchronously executes all due tasks in order.
     */
    public synchronized void advanceTime(long duration, TimeUnit unit) {
        long targetTimeMs = currentTimeMs + unit.toMillis(duration);
        currentTimeMs = targetTimeMs;

        while (!taskQueue.isEmpty() && taskQueue.peek().triggerTimeMs <= currentTimeMs) {
            ScheduledTask<?> due = taskQueue.poll();
            if (!due.isCancelled()) {
                due.execute();
            }
        }
    }

    public void tick(long duration, TimeUnit unit) {
        advanceTime(duration, unit);
    }

    @Override
    public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        long triggerTimeMs = currentTimeMs + unit.toMillis(delay);
        ScheduledTask<Void> task = new ScheduledTask<>(command, triggerTimeMs, sequenceGenerator.incrementAndGet());
        taskQueue.add(task);
        return task;
    }

    @Override
    public synchronized <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        long triggerTimeMs = currentTimeMs + unit.toMillis(delay);
        ScheduledTask<V> task = new ScheduledTask<>(callable, triggerTimeMs, sequenceGenerator.incrementAndGet());
        taskQueue.add(task);
        return task;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return schedule(command, initialDelay, unit);
    }

    @Override
    public void shutdown() {
        isShutdown.set(true);
    }

    @Override
    public List<Runnable> shutdownNow() {
        isShutdown.set(true);
        List<Runnable> pending = new ArrayList<>();
        synchronized (this) {
            while (!taskQueue.isEmpty()) {
                ScheduledTask<?> task = taskQueue.poll();
                if (!task.isCancelled() && task.runnable != null) {
                    pending.add(task.runnable);
                }
            }
        }
        return pending;
    }

    @Override
    public boolean isShutdown() {
        return isShutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return isShutdown.get() && taskQueue.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        ScheduledFuture<T> f = schedule(() -> {
            task.run();
            return result;
        }, 0, TimeUnit.MILLISECONDS);
        advanceTime(0, TimeUnit.MILLISECONDS);
        return f;
    }

    @Override
    public Future<?> submit(Runnable task) {
        ScheduledFuture<?> f = schedule(task, 0, TimeUnit.MILLISECONDS);
        advanceTime(0, TimeUnit.MILLISECONDS);
        return f;
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void execute(Runnable command) {
        submit(command);
    }

    private static class ScheduledTask<V> implements ScheduledFuture<V> {
        final Runnable runnable;
        final Callable<V> callable;
        final long triggerTimeMs;
        final long sequence;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean done = new AtomicBoolean(false);
        private V result = null;
        private Throwable exception = null;

        ScheduledTask(Runnable runnable, long triggerTimeMs, long sequence) {
            this.runnable = runnable;
            this.callable = null;
            this.triggerTimeMs = triggerTimeMs;
            this.sequence = sequence;
        }

        ScheduledTask(Callable<V> callable, long triggerTimeMs, long sequence) {
            this.runnable = null;
            this.callable = callable;
            this.triggerTimeMs = triggerTimeMs;
            this.sequence = sequence;
        }

        void execute() {
            if (cancelled.get() || done.get()) return;
            try {
                if (runnable != null) {
                    runnable.run();
                } else if (callable != null) {
                    result = callable.call();
                }
            } catch (Throwable t) {
                exception = t;
            } finally {
                done.set(true);
            }
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed o) {
            if (o instanceof ScheduledTask<?> other) {
                int cmp = Long.compare(this.triggerTimeMs, other.triggerTimeMs);
                return cmp != 0 ? cmp : Long.compare(this.sequence, other.sequence);
            }
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public boolean isDone() {
            return done.get() || cancelled.get();
        }

        @Override
        public V get() throws ExecutionException {
            if (exception != null) throw new ExecutionException(exception);
            return result;
        }

        @Override
        public V get(long timeout, TimeUnit unit) throws ExecutionException {
            return get();
        }
    }
}
