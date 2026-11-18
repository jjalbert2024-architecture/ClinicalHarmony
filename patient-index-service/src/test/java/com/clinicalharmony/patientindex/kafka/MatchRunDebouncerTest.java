package com.clinicalharmony.patientindex.kafka;

import com.clinicalharmony.patientindex.repository.MatchRun;
import com.clinicalharmony.patientindex.service.ConflictException;
import com.clinicalharmony.patientindex.service.MatchingRunService;
import com.clinicalharmony.patientindex.service.RunResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The 9c debounce rule on a hand-moved clock: quiet 5 s after the last event, at most 30 s after the first. */
class MatchRunDebouncerTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-18T09:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final MatchingRunService runService = mock(MatchingRunService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private MatchRunDebouncer debouncer;

    @BeforeEach
    void setUp() {
        PmiMatcherProperties props = new PmiMatcherProperties(true, 3, Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofMillis(500), Duration.ofSeconds(30), true,
                new PmiMatcherProperties.Retry(3, Duration.ofSeconds(1), 2.0));
        debouncer = new MatchRunDebouncer(runService, jdbc, props, new SimpleMeterRegistry(), clock);
        when(runService.runIfIdle(anyString())).thenReturn(result());
    }

    private static RunResult result() {
        return new RunResult(new MatchRun(41L, "COMPLETED", "kafka", null, null, 0, 0, 0, 0, 0, null),
                List.of(), List.of(), List.of(), List.of(), 0);
    }

    @Test
    void nothingPendingNeverRuns() {
        clock.advance(Duration.ofMinutes(5));
        debouncer.tick();
        verify(runService, never()).runIfIdle(anyString());
    }

    @Test
    void oneRunFiveSecondsAfterTheLastEventOfABurst() {
        debouncer.signal(1, "mllp-a");
        clock.advance(Duration.ofSeconds(2));
        debouncer.signal(2, "mllp-b");
        clock.advance(Duration.ofSeconds(2));
        debouncer.signal(2, "mllp-c");

        clock.advance(Duration.ofMillis(4900));
        debouncer.tick();
        verify(runService, never()).runIfIdle(anyString());

        clock.advance(Duration.ofMillis(100));
        debouncer.tick();
        verify(runService).runIfIdle("kafka:pmi-matcher (3 events, 2 masters)");

        clock.advance(Duration.ofSeconds(10));
        debouncer.tick();
        verify(runService, times(1)).runIfIdle(anyString());
    }

    @Test
    void aSteadyStreamStillRunsEveryThirtySeconds() {
        for (int i = 0; i < 31; i++) {   // one event a second for 31 s: never 5 s of quiet
            debouncer.signal(i, null);
            debouncer.tick();
            clock.advance(Duration.ofSeconds(1));
        }
        // the 31st event (t = 30 s) finds the first one 30 s old: run now, quiet or not
        verify(runService, times(1)).runIfIdle("kafka:pmi-matcher (31 events, 31 masters)");
    }

    @Test
    void busyLockOrFailedRunIsRetriedAfterTheRetryDelayWithTheEventsKept() {
        when(runService.runIfIdle(anyString()))
                .thenThrow(new ConflictException("busy"))
                .thenThrow(new IllegalStateException("GoldWriter down"))
                .thenReturn(result());
        debouncer.signal(7, null);
        clock.advance(Duration.ofSeconds(5));
        debouncer.tick();                                  // 1: busy
        clock.advance(Duration.ofSeconds(29));
        debouncer.tick();                                  // still inside the retry delay
        verify(runService, times(1)).runIfIdle(anyString());
        clock.advance(Duration.ofSeconds(1));
        debouncer.signal(8, null);                         // arrives meanwhile: merged into the retried batch
        clock.advance(Duration.ofSeconds(5));
        debouncer.tick();                                  // 2: fails
        clock.advance(Duration.ofSeconds(30));
        debouncer.tick();                                  // 3: runs
        verify(runService).runIfIdle("kafka:pmi-matcher (1 event, 1 master)");
        verify(runService, times(2)).runIfIdle("kafka:pmi-matcher (2 events, 2 masters)");
    }

    @Test
    void startupCatchUpOnlyWhenProvisionalMastersAreWaiting() {
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(0L, 4L);
        debouncer.onStartup();
        clock.advance(Duration.ofSeconds(6));
        debouncer.tick();
        verify(runService, never()).runIfIdle(anyString());

        debouncer.onStartup();
        clock.advance(Duration.ofSeconds(5));
        debouncer.tick();
        verify(runService).runIfIdle("kafka:pmi-matcher (startup catch-up)");
    }
}
