/*
 * Copyright © 2026 Plasticity.Cloud and CoDriverLabs. All rights reserved.
 */
package cloud.plasticity.jobrunr.build;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Tracks how many jobs a worker has picked up and finished.
 *
 * <p>An ephemeral worker exists to run one build and then exit. It needs two signals to decide when
 * that is done: how many jobs have terminated, and whether anything was ever claimed. The second
 * matters because a task can lose the race for its own job to a concurrently running task of the
 * same architecture — work stealing is harmless, but the loser must not sit idle and keep billing.
 */
public final class JobCompletionTracker {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger finished = new AtomicInteger();

    /** Called by the handler when a job begins executing. */
    public void jobStarted() {
        signal(started);
    }

    /** Called by the handler when a job terminates, successfully or not. */
    public void jobFinished() {
        signal(finished);
    }

    public int startedCount() {
        return started.get();
    }

    public int finishedCount() {
        return finished.get();
    }

    /**
     * Waits until {@code expected} jobs have finished.
     *
     * @param idleTimeout how long to wait for the <em>first</em> job to be claimed before giving up;
     *                    {@code null} disables the idle check
     * @return true when the expected number of jobs finished, false on either timeout
     */
    public boolean awaitCompletion(int expected, Duration overallTimeout, Duration idleTimeout)
            throws InterruptedException {
        if (expected <= 0) {
            return true;
        }
        long deadlineNanos = System.nanoTime() + overallTimeout.toNanos();
        Long idleDeadlineNanos =
                idleTimeout == null ? null : System.nanoTime() + idleTimeout.toNanos();

        lock.lock();
        try {
            while (finished.get() < expected) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    return false;
                }
                if (idleDeadlineNanos != null && started.get() == 0) {
                    long idleRemaining = idleDeadlineNanos - System.nanoTime();
                    if (idleRemaining <= 0) {
                        return false;
                    }
                    remainingNanos = Math.min(remainingNanos, idleRemaining);
                }
                changed.awaitNanos(remainingNanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void signal(AtomicInteger counter) {
        lock.lock();
        try {
            counter.incrementAndGet();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
