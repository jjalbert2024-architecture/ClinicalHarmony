package com.clinicalharmony.gateway.read;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Where a claim is now (silver.claims lifecycle columns, migration 28) and who has to act next. "Waiting on" is
 * derived here, not stored: it's a reading of status + amounts that can change without touching the data.
 */
public final class ClaimLifecycle {

    /** Who the claim is waiting on. */
    public enum WaitingOn {
        /** Submitted / acknowledged / pended / under appeal: the payer has to adjudicate. */
        PAYER,
        /** Rejected or denied for a provider-side reason (CO-*, OA-*, …): fix and resubmit, or appeal. */
        PROVIDER,
        /** Paid with a balance the patient owes, or denied as patient responsibility (PR-*). */
        PATIENT,
        /** Paid in full or voided: closed. */
        NOBODY
    }

    /**
     * A claim line with its lifecycle; every lifecycle field is null when the feed didn't carry it (pre-lifecycle
     * files), and so is {@code waitingOn}.
     *
     * @param waitingSince when the ball landed in {@code waitingOn}'s court: the submitted date while the payer has it
     *                     (status date if not submitted-dated), else the status date
     */
    public record ClaimDetail(String claimId, String payerId, String payerName, String status, String statusReason,
                              BigDecimal billedAmount, BigDecimal allowedAmount, BigDecimal paidAmount,
                              BigDecimal patientResponsibility, LocalDate serviceDate, LocalDate submittedDate,
                              LocalDate statusDate, WaitingOn waitingOn, LocalDate waitingSince) {
    }

    private ClaimLifecycle() {
    }

    public static ClaimDetail detail(String claimId, String payerId, String payerName, String status,
                                     String statusReason, BigDecimal billed, BigDecimal allowed, BigDecimal paid,
                                     BigDecimal patientResponsibility, LocalDate serviceDate, LocalDate submittedDate,
                                     LocalDate statusDate) {
        WaitingOn on = waitingOn(status, statusReason, patientResponsibility);
        LocalDate since = on == null || on == WaitingOn.NOBODY ? null
                : on == WaitingOn.PAYER && submittedDate != null && !"APPEALED".equals(status) ? submittedDate
                : statusDate;
        return new ClaimDetail(claimId, payerId, payerName, status, statusReason, billed, allowed, paid,
                patientResponsibility, serviceDate, submittedDate, statusDate, on, since);
    }

    static WaitingOn waitingOn(String status, String statusReason, BigDecimal patientResponsibility) {
        if (status == null) {
            return null;
        }
        boolean patientOwes = patientResponsibility != null && patientResponsibility.signum() > 0;
        return switch (status) {
            case "SUBMITTED", "ACCEPTED", "PENDED", "APPEALED" -> WaitingOn.PAYER;
            case "REJECTED" -> WaitingOn.PROVIDER;
            case "DENIED" -> statusReason != null && statusReason.startsWith("PR-") ? WaitingOn.PATIENT : WaitingOn.PROVIDER;
            case "PAID", "PARTIALLY_PAID" -> patientOwes ? WaitingOn.PATIENT : WaitingOn.NOBODY;
            case "VOIDED" -> WaitingOn.NOBODY;
            default -> null;
        };
    }
}
