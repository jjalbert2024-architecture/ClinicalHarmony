package com.clinicalharmony.silver.store;

import com.clinicalharmony.silver.config.SilverProperties;
import com.clinicalharmony.silver.listener.BronzeIngestedEvent;
import com.clinicalharmony.silver.validation.PatientValidator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Heals Bronze rows the Kafka path left behind: MLLP_KAFKA / REST_KAFKA rows still PENDING after
 * {@code silver.backlog.min-age} — the FHIR / claims / C-CDA rows stored during 9d part 1 (never announced again),
 * or any row whose {@code bronze.ingested} event was lost. Same code path as the listener ({@link SilverWriter},
 * then validation after the commit), so it's idempotent and safe to race the listener (the row is locked and
 * only a PENDING one is transformed). Legacy REST rows (written directly before 9d) are never touched.
 */
@Component
@ConditionalOnProperty(name = "silver.backlog.enabled", havingValue = "true", matchIfMissing = true)
public class BronzeBacklogSweeper {

    private static final Logger log = LoggerFactory.getLogger(BronzeBacklogSweeper.class);

    private final JdbcTemplate jdbc;
    private final SilverWriter writer;
    private final PatientValidator validator;
    private final SilverProperties props;
    private final MeterRegistry meters;
    private final AtomicLong stranded = new AtomicLong();

    public BronzeBacklogSweeper(JdbcTemplate jdbc, SilverWriter writer, PatientValidator validator, SilverProperties props,
                                MeterRegistry meters) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.validator = validator;
        this.props = props;
        this.meters = meters;
        Gauge.builder("clinicalharmony.silver.backlog.pending", stranded, AtomicLong::get)
                .description("Kafka-fed Bronze rows PENDING longer than silver.backlog.min-age").register(meters);
    }

    /** First sweep {@code silver.backlog.initial-delay} (15 s) after startup — the catch-up — then every interval. */
    @Scheduled(initialDelayString = "${silver.backlog.initial-delay:15s}", fixedDelayString = "${silver.backlog.interval}")
    public void sweep() {
        try {
            List<Map<String, Object>> due = jdbc.queryForList("""
                    SELECT id, message_type, source_system, correlation_id FROM bronze.raw_messages
                    WHERE processing_status = 'PENDING' AND intake_channel IN ('MLLP_KAFKA', 'REST_KAFKA')
                      AND created_at < now() - make_interval(secs => ?)
                    ORDER BY id
                    LIMIT ?
                    """, props.backlog().minAge().toMillis() / 1000.0, props.backlog().batchSize());
            int transformed = 0;
            int failed = 0;
            int errors = 0;
            for (Map<String, Object> row : due) {
                long id = ((Number) row.get("id")).longValue();
                String correlationId = (String) row.get("correlation_id");
                MDC.put("correlationId", correlationId == null ? "" : correlationId);
                try {
                    // One row's technical failure (a mapper bug, a DB hiccup) must not block the rows behind it.
                    SilverWriter.Result r = writer.transform(
                            new BronzeIngestedEvent(id, (String) row.get("message_type"), null,
                                    (String) row.get("source_system"), null, null),
                            new KafkaSource("backlog-sweeper", -1, -1, null, null, correlationId));
                    meters.counter("clinicalharmony.silver.messages", "outcome", r.outcome().name().toLowerCase()).increment();
                    if (r.outcome() == SilverWriter.Outcome.TRANSFORMED) {
                        transformed++;
                        validator.validate(r.silverPatientId());
                    } else if (r.outcome() == SilverWriter.Outcome.FAILED) {
                        failed++;
                    }
                    log.atInfo().addKeyValue("event", "silver.backlog_swept")
                            .addKeyValue("rawMessageId", id)
                            .addKeyValue("bronzeMessageType", row.get("message_type"))
                            .addKeyValue("outcome", r.outcome())
                            .addKeyValue("silverPatientId", r.silverPatientId())
                            .addKeyValue("reason", r.error())
                            .log("Stranded Bronze row transformed by the backlog sweeper");
                } catch (Exception e) {
                    errors++;
                    log.atWarn().addKeyValue("event", "silver.backlog_row_failed")
                            .addKeyValue("rawMessageId", id)
                            .addKeyValue("reason", e.getClass().getSimpleName() + ": " + e.getMessage())
                            .log("Backlog sweeper could not transform this row; it stays PENDING and is retried next sweep");
                } finally {
                    MDC.remove("correlationId");
                }
            }
            Long n = jdbc.queryForObject("""
                    SELECT count(*) FROM bronze.raw_messages
                    WHERE processing_status = 'PENDING' AND intake_channel IN ('MLLP_KAFKA', 'REST_KAFKA')
                      AND created_at < now() - make_interval(secs => ?)
                    """, Long.class, props.backlog().minAge().toMillis() / 1000.0);
            stranded.set(n == null ? 0 : n);
            if (!due.isEmpty()) {
                log.atInfo().addKeyValue("event", "silver.backlog_sweep")
                        .addKeyValue("due", due.size()).addKeyValue("transformed", transformed)
                        .addKeyValue("failed", failed).addKeyValue("errors", errors).addKeyValue("stillStranded", stranded.get())
                        .log("Bronze backlog sweep finished");
            }
        } catch (Exception e) {
            log.atWarn().addKeyValue("event", "silver.backlog_sweep_failed").addKeyValue("reason", e.getMessage())
                    .log("Bronze backlog sweep failed; next sweep tries again");
        }
    }
}
