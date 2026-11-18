package com.clinicalharmony.patientindex.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes matching runs and review decisions within this instance, so a reviewer's merge can't
 * interleave with a run's view of the same masters. An API run or a review waits a few seconds for the
 * lock, then 409s; the Phase 9c pmi-matcher's debounced run ({@link #tryExclusively}) doesn't wait at all —
 * it simply tries again later. GoldWriter's row locks still guard Gold itself
 * (single-instance service; across instances its 409s are the backstop).
 */
@Component
public class PmiLock {

    private static final long REVIEW_WAIT_SECONDS = 5;
    /**
     * Since 9c a debounced run may be going when someone clicks "Run matching": wait for it (runs take
     * seconds at this scale) rather than refusing straight away.
     */
    private static final long RUN_WAIT_SECONDS = 15;
    private final ReentrantLock lock = new ReentrantLock();

    public <T> T runExclusively(Supplier<T> work) {
        if (!acquire(RUN_WAIT_SECONDS)) {
            throw new ConflictException("A matching run or review decision is already in progress; try again shortly");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    /** Like {@link #runExclusively} but never waits: throws {@link ConflictException} at once if the lock is held. */
    public <T> T tryExclusively(Supplier<T> work) {
        if (!lock.tryLock()) {
            throw new ConflictException("A matching run or review decision is already in progress");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private boolean acquire(long seconds) {
        try {
            return lock.tryLock(seconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public <T> T reviewExclusively(Supplier<T> work) {
        try {
            if (!lock.tryLock(REVIEW_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new ConflictException("A matching run is in progress; try the review decision again shortly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConflictException("Interrupted waiting for a matching run to finish");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
