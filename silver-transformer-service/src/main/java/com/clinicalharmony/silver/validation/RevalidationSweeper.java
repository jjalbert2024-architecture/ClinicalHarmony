package com.clinicalharmony.silver.validation;

import com.clinicalharmony.silver.config.SilverProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Re-validates Silver patients left PENDING_VALIDATION (rules-engine or ontology-service was down when
 * their message was transformed, or the service stopped between commit and validation). Oldest request
 * first; patients requested less than {@code silver.validation.min-age} ago are left to the listener,
 * which validates right after its own commit. Stops the batch at the first deferral: if rules-engine is
 * still down, the rest would only fail the same way.
 */
@Component
public class RevalidationSweeper {

    private static final Logger log = LoggerFactory.getLogger(RevalidationSweeper.class);

    private final JdbcTemplate jdbc;
    private final PatientValidator validator;
    private final SilverProperties props;
    private final AtomicLong pending = new AtomicLong();

    public RevalidationSweeper(JdbcTemplate jdbc, PatientValidator validator, SilverProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.props = props;
        Gauge.builder("clinicalharmony.silver.validation.pending", pending, AtomicLong::get)
                .description("Silver patients waiting for (re-)validation").register(meters);
    }

    @Scheduled(fixedDelayString = "${silver.validation.sweep-interval}", initialDelayString = "${silver.validation.sweep-interval}")
    public void sweep() {
        try {
            List<Long> due = jdbc.queryForList("""
                    SELECT id FROM silver.patients
                    WHERE validation_status = 'PENDING_VALIDATION'
                      AND validation_requested_at < now() - make_interval(secs => ?)
                    ORDER BY validation_requested_at, id
                    LIMIT ?
                    """, Long.class, props.validation().minAge().toMillis() / 1000.0, props.validation().batchSize());
            int validated = 0;
            for (long id : due) {
                if (validator.validate(id).outcome() != PatientValidator.Outcome.VALIDATED) {
                    break;
                }
                validated++;
            }
            Long n = jdbc.queryForObject("SELECT count(*) FROM silver.patients WHERE validation_status = 'PENDING_VALIDATION'",
                    Long.class);
            pending.set(n == null ? 0 : n);
            if (!due.isEmpty()) {
                log.atInfo().addKeyValue("event", "silver.revalidation_sweep")
                        .addKeyValue("due", due.size())
                        .addKeyValue("validated", validated)
                        .addKeyValue("stillPending", pending.get())
                        .log("Re-validation sweep finished");
            }
        } catch (Exception e) {
            log.atWarn().addKeyValue("event", "silver.revalidation_sweep_failed").addKeyValue("reason", e.getMessage())
                    .log("Re-validation sweep failed; next sweep tries again");
        }
    }
}
