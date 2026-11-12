package com.clinicalharmony.patientindex.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes matching runs and review decisions within this instance, so a reviewer's merge can't
 * interleave with a run's view of the same masters. A run fails fast if one is already going
 * (409); a review waits a few seconds first. GoldWriter's row locks still guard Gold itself
 * (single-instance service; across instances its 409s are the backstop).
 */
@Component
public class PmiLock {

    private static final long REVIEW_WAIT_SECONDS = 5;
    private final ReentrantLock lock = new ReentrantLock();

    public <T> T runExclusively(Supplier<T> work) {
        if (!lock.tryLock()) {
            throw new ConflictException("A matching run or review decision is already in progress; try again shortly");
        }
        try {
            return work.get();
        } finally {
            lock.unlock();
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
