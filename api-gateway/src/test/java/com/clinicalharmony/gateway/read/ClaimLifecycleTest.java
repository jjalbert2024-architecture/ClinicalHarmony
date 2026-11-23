package com.clinicalharmony.gateway.read;

import com.clinicalharmony.gateway.read.ClaimLifecycle.WaitingOn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimLifecycleTest {

    private static final LocalDate SENT = LocalDate.of(2026, 11, 3);
    private static final LocalDate CHANGED = LocalDate.of(2026, 11, 20);

    private static ClaimLifecycle.ClaimDetail claim(String status, String reason, String patientOwes) {
        return ClaimLifecycle.detail("CLM-1", "60054", "AETNA", status, reason, new BigDecimal("120.00"), null, null,
                patientOwes == null ? null : new BigDecimal(patientOwes), LocalDate.of(2026, 11, 1), SENT, CHANGED);
    }

    @Test
    void payerHasItUntilAdjudicatedCountingFromSubmission() {
        for (String s : new String[] {"SUBMITTED", "ACCEPTED", "PENDED"}) {
            assertThat(claim(s, null, null)).satisfies(c -> {
                assertThat(c.waitingOn()).isEqualTo(WaitingOn.PAYER);
                assertThat(c.waitingSince()).isEqualTo(SENT);
            });
        }
        // An appeal restarts the payer's clock at the appeal (the status date), not the original submission.
        assertThat(claim("APPEALED", "CO-50", null).waitingSince()).isEqualTo(CHANGED);
    }

    @Test
    void denialsGoToTheProviderUnlessThePatientIsResponsible() {
        assertThat(claim("DENIED", "CO-50", "0").waitingOn()).isEqualTo(WaitingOn.PROVIDER);
        assertThat(claim("REJECTED", null, null).waitingOn()).isEqualTo(WaitingOn.PROVIDER);
        assertThat(claim("DENIED", "PR-96", "120.00").waitingOn()).isEqualTo(WaitingOn.PATIENT);
        assertThat(claim("DENIED", "CO-16", null).waitingSince()).isEqualTo(CHANGED);
    }

    @Test
    void paidClaimsWaitOnThePatientOnlyForABalance() {
        assertThat(claim("PAID", "PR-1", "20.00").waitingOn()).isEqualTo(WaitingOn.PATIENT);
        assertThat(claim("PARTIALLY_PAID", "CO-45", "0.00").waitingOn()).isEqualTo(WaitingOn.NOBODY);
        assertThat(claim("PAID", null, null)).satisfies(c -> {
            assertThat(c.waitingOn()).isEqualTo(WaitingOn.NOBODY);
            assertThat(c.waitingSince()).isNull();
        });
        assertThat(claim("VOIDED", null, null).waitingOn()).isEqualTo(WaitingOn.NOBODY);
    }

    @Test
    void noStatusMeansUnknown() {
        assertThat(claim(null, null, null)).satisfies(c -> {
            assertThat(c.waitingOn()).isNull();
            assertThat(c.waitingSince()).isNull();
        });
    }
}
