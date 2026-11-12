package com.clinicalharmony.fhircompliance.gold;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Validates any Gold rows still pending validation (migration 19's seed) once the app is up, so a
 * fresh database's CMS-0057 resources carry real compliance verdicts without anyone having to call
 * {@code POST /api/fhir/gold/revalidate} first. Failures are logged, never fatal — the endpoint can
 * retry. Disable with {@code gold-writer.revalidate-pending-on-startup=false}.
 */
@Component
@ConditionalOnProperty(name = "gold-writer.revalidate-pending-on-startup", havingValue = "true", matchIfMissing = true)
public class GoldRevalidationRunner {

    private static final Logger log = LoggerFactory.getLogger(GoldRevalidationRunner.class);

    private final GoldRevalidationService revalidationService;

    public GoldRevalidationRunner(GoldRevalidationService revalidationService) {
        this.revalidationService = revalidationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void revalidatePendingOnStartup() {
        MDC.put("requestId", "startup");
        try {
            revalidationService.revalidatePending();
        } catch (RuntimeException e) {
            log.warn("Startup validation of pending Gold rows failed (retry via POST /api/fhir/gold/revalidate): {}",
                    e.getMessage());
        } finally {
            MDC.remove("requestId");
        }
    }
}
