package com.clinicalharmony.patientindex.kafka;

import com.clinicalharmony.patientindex.service.ConflictException;
import com.clinicalharmony.patientindex.service.MatchingRunService;
import com.clinicalharmony.patientindex.service.RunResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a stream of {@code gold.patient.changed} events into few matching runs (Phase 9c decision):
 * <b>one run {@code quietPeriod} (5 s) after the last event of a burst, and at most {@code maxDelay} (30 s)
 * after the first pending one</b> — so a long burst still gets a run every 30 s instead of waiting for silence.
 *
 * <p>Why not one run per event: a run looks at every PROVISIONAL master anyway (pairs need both sides
 * present), and runs are serialized by the PMI lock, so per-event runs would mostly re-do the same work —
 * and a generator burst of a hundred messages would queue a hundred runs.
 *
 * <p>The listener only {@link #signal}s; {@link #tick} (every {@code tick}, on the scheduler thread) decides and
 * runs {@link MatchingRunService#runIfIdle}, which never waits for the lock. If the lock is busy (a manual run or
 * a review) or the run fails (GoldWriter unreachable), the batch is put back and retried after
 * {@code retryDelay}. Offsets are committed when an event is signalled, not after its run: a crash in between
 * loses only the trigger, never data, and {@link #onStartup} schedules a catch-up run whenever PROVISIONAL masters
 * are waiting.
 */
@Component
@ConditionalOnProperty(prefix = "pmi-matcher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MatchRunDebouncer {

    private static final Logger log = LoggerFactory.getLogger(MatchRunDebouncer.class);

    /** What a due run covers — for the run's {@code triggered_by} and the log line. */
    record Batch(int events, Set<Long> masters, Instant firstAt, Instant lastAt, String lastCorrelationId,
                 boolean catchUp) {
    }

    private final MatchingRunService runService;
    private final JdbcTemplate jdbc;
    private final PmiMatcherProperties props;
    private final MeterRegistry meters;
    private final Clock clock;

    // Guarded by this.
    private int events;
    private final Set<Long> masters = new TreeSet<>();
    private Instant firstAt;
    private Instant lastAt;
    private Instant notBefore = Instant.EPOCH;
    private String lastCorrelationId;
    private boolean catchUp;

    @Autowired
    public MatchRunDebouncer(MatchingRunService runService, JdbcTemplate jdbc, PmiMatcherProperties props,
                             MeterRegistry meters) {
        this(runService, jdbc, props, meters, Clock.systemUTC());
    }

    MatchRunDebouncer(MatchingRunService runService, JdbcTemplate jdbc, PmiMatcherProperties props,
                      MeterRegistry meters, Clock clock) {
        this.runService = runService;
        this.jdbc = jdbc;
        this.props = props;
        this.meters = meters;
        this.clock = clock;
    }

    /** A Gold patient changed: (re)start the quiet period. */
    public synchronized void signal(long masterPatientId, String correlationId) {
        touch();
        events++;
        masters.add(masterPatientId);
        if (correlationId != null) {
            lastCorrelationId = correlationId;
        }
    }

    /** A run is wanted with no event behind it (startup catch-up). */
    synchronized void signalCatchUp() {
        touch();
        catchUp = true;
    }

    private void touch() {
        Instant now = clock.instant();
        if (firstAt == null) {
            firstAt = now;
        }
        lastAt = now;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.runOnStartup()) {
            return;
        }
        try {
            Long provisional = jdbc.queryForObject(
                    "SELECT count(*) FROM gold.master_patients WHERE is_active AND match_status = 'PROVISIONAL'", Long.class);
            if (provisional != null && provisional > 0) {
                log.atInfo().addKeyValue("event", "pmi.catch_up_scheduled").addKeyValue("provisionalMasters", provisional)
                        .log("PROVISIONAL masters waiting at startup; scheduling a matching run");
                signalCatchUp();
            }
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("event", "pmi.catch_up_check_failed").addKeyValue("reason", e.getMessage())
                    .log("Couldn't check for PROVISIONAL masters at startup");
        }
    }

    @Scheduled(fixedDelayString = "${pmi-matcher.tick}")
    public void tick() {
        Optional<Batch> due = takeIfDue();
        due.ifPresent(this::runBatch);
    }

    /** The pending batch, cleared, if a run is due now. */
    synchronized Optional<Batch> takeIfDue() {
        if (firstAt == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (now.isBefore(notBefore)) {
            return Optional.empty();
        }
        boolean quiet = !now.isBefore(lastAt.plus(props.quietPeriod()));
        boolean overdue = !now.isBefore(firstAt.plus(props.maxDelay()));
        if (!quiet && !overdue) {
            return Optional.empty();
        }
        Batch batch = new Batch(events, new TreeSet<>(masters), firstAt, lastAt, lastCorrelationId, catchUp);
        events = 0;
        masters.clear();
        firstAt = null;
        lastAt = null;
        lastCorrelationId = null;
        catchUp = false;
        return Optional.of(batch);
    }

    /** Puts a batch whose run didn't happen back, merged with anything that arrived meanwhile. */
    synchronized void requeue(Batch batch) {
        Instant now = clock.instant();
        events += batch.events();
        masters.addAll(batch.masters());
        firstAt = firstAt == null || batch.firstAt().isBefore(firstAt) ? batch.firstAt() : firstAt;
        lastAt = lastAt == null || batch.lastAt().isAfter(lastAt) ? batch.lastAt() : lastAt;
        if (lastCorrelationId == null) {
            lastCorrelationId = batch.lastCorrelationId();
        }
        catchUp |= batch.catchUp();
        notBefore = now.plus(props.retryDelay());
    }

    static String triggeredBy(Batch batch) {
        if (batch.events() == 0) {
            return "kafka:pmi-matcher (startup catch-up)";
        }
        return "kafka:pmi-matcher (" + batch.events() + " event" + (batch.events() == 1 ? "" : "s")
                + ", " + batch.masters().size() + " master" + (batch.masters().size() == 1 ? "" : "s") + ")";
    }

    void runBatch(Batch batch) {
        Instant start = clock.instant();
        String triggeredBy = triggeredBy(batch);
        MDC.put("requestId", batch.lastCorrelationId() == null ? "pmi-matcher" : batch.lastCorrelationId());
        try {
            RunResult result = runService.runIfIdle(triggeredBy);
            log.atInfo().addKeyValue("event", "pmi.run")
                    .addKeyValue("runId", result.run().id())
                    .addKeyValue("trigger", "kafka")
                    .addKeyValue("events", batch.events())
                    .addKeyValue("changedMasters", batch.masters().size())
                    .addKeyValue("autoMerges", result.autoMerges().size())
                    .addKeyValue("queuedForReview", result.queuedCandidateIds().size())
                    .addKeyValue("confirmed", result.confirmedMasterIds().size())
                    .addKeyValue("skipped", result.skipped().size())
                    .addKeyValue("waitedMs", Duration.between(batch.firstAt(), start).toMillis())
                    .addKeyValue("durationMs", Duration.between(start, clock.instant()).toMillis())
                    .log("Debounced matching run after gold.patient.changed events");
            meters.counter("clinicalharmony.pmi.runs", "trigger", "kafka", "outcome", "completed").increment();
            meters.counter("clinicalharmony.pmi.auto_merges").increment(result.autoMerges().size());
            meters.counter("clinicalharmony.pmi.reviews_queued").increment(result.queuedCandidateIds().size());
        } catch (ConflictException e) {
            requeue(batch);
            log.atInfo().addKeyValue("event", "pmi.run_deferred").addKeyValue("reason", e.getMessage())
                    .addKeyValue("retryIn", props.retryDelay().toString())
                    .log("PMI busy (manual run or review); debounced run deferred");
            meters.counter("clinicalharmony.pmi.runs", "trigger", "kafka", "outcome", "deferred").increment();
        } catch (RuntimeException e) {
            requeue(batch);
            log.atWarn().addKeyValue("event", "pmi.run_failed").addKeyValue("reason", e.getMessage())
                    .addKeyValue("retryIn", props.retryDelay().toString())
                    .log("Debounced matching run failed; will retry");
            meters.counter("clinicalharmony.pmi.runs", "trigger", "kafka", "outcome", "failed").increment();
        } finally {
            MDC.remove("requestId");
        }
    }
}
