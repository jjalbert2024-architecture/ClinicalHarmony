package com.clinicalharmony.silver.map;

import com.clinicalharmony.silver.hl7.MappingException;
import com.clinicalharmony.silver.hl7.SilverRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ClaimsSilverMapperTest {

    public static final String HEADER = "claim_id,member_id,member_first_name,member_last_name,member_dob,member_gender,"
            + "member_zip,service_date,icd10_code,billed_amount";
    public static final String LINE = "CLM-1,MBR-77,Robert,Smith,1955-07-01,M,62704,2026-11-01,E11.9,150.00";

    private final ClaimsSilverMapper mapper = new ClaimsSilverMapper();

    @Test
    void lineBecomesMemberConditionAndClaim() {
        SilverRecord r = mapper.map(HEADER + "\n" + LINE, "CLAIMS_VENDOR_A");
        assertThat(r.messageType()).isEqualTo("CLAIM");
        assertThat(r.updatesDemographics()).isFalse();   // a claim never overwrites registration data
        assertThat(r.patient()).satisfies(p -> {
            assertThat(p.sourceSystem()).isEqualTo("CLAIMS_VENDOR_A");
            assertThat(p.sourcePatientId()).isEqualTo("MBR-77");
            assertThat(p.mrn()).isNull();
            assertThat(p.firstName()).isEqualTo("Robert");
            assertThat(p.dateOfBirth()).isEqualTo(LocalDate.of(1955, 7, 1));
            assertThat(p.gender()).isEqualTo("male");
            assertThat(p.postalCode()).isEqualTo("62704");
        });
        assertThat(r.conditions()).singleElement().satisfies(c -> {
            assertThat(c.codeSystem()).isEqualTo("ICD-10");
            assertThat(c.code()).isEqualTo("E11.9");
            assertThat(c.onsetDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        });
        assertThat(r.claims()).singleElement().satisfies(c -> {
            assertThat(c.claimId()).isEqualTo("CLM-1");
            assertThat(c.billedAmount()).isEqualByComparingTo("150.00");
        });
    }

    @Test
    void quotedValuesReadBack() {
        SilverRecord r = mapper.map(HEADER + "\nCLM-2,MBR-8,\"Mary, Jo\",O'Neil,1960-02-02,f,,2026-11-02,I10,95.5", "S");
        assertThat(r.patient().firstName()).isEqualTo("Mary, Jo");
        assertThat(r.patient().gender()).isEqualTo("female");
        assertThat(r.patient().postalCode()).isNull();
    }

    @Test
    void preMemberColumnFormatFails() {
        assertThatThrownBy(() -> mapper.map("claim_id,member_id,service_date,icd10_code,billed_amount\n"
                + "CLM-1,MBR-1,2026-06-01,E11.9,150.00", "S"))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("no member columns [member_first_name, member_last_name, member_dob, member_gender]");
    }

    @Test
    void badValuesFail() {
        assertThatThrownBy(() -> mapper.map(HEADER + "\n" + LINE.replace("1955-07-01", "07/01/1955"), "S"))
                .hasMessage("member_dob '07/01/1955' is not a YYYY-MM-DD date");
        assertThatThrownBy(() -> mapper.map(HEADER + "\n" + LINE.replace(",M,", ",Q,"), "S"))
                .hasMessageContaining("member_gender 'Q'");
        assertThatThrownBy(() -> mapper.map(HEADER + "\n" + LINE.replace("150.00", "-5"), "S"))
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> mapper.map(HEADER + "\n" + LINE.replace("E11.9", ""), "S"))
                .hasMessage("icd10_code is empty");
    }

    public static final String LIFECYCLE_HEADER = HEADER + ",payer_id,payer_name,claim_status,submitted_date,status_date,"
            + "allowed_amount,paid_amount,patient_responsibility,status_reason";

    @Test
    void lifecycleColumnsAreRead() {
        SilverRecord r = mapper.map(LIFECYCLE_HEADER + "\n" + LINE
                + ",60054,AETNA,denied,2026-11-03,2026-11-20,0.00,0.00,0.00,co-50", "S");
        assertThat(r.claims()).singleElement().satisfies(c -> {
            SilverRecord.Lifecycle l = c.lifecycle();
            assertThat(l.payerId()).isEqualTo("60054");
            assertThat(l.payerName()).isEqualTo("AETNA");
            assertThat(l.status()).isEqualTo("DENIED");
            assertThat(l.submittedDate()).isEqualTo(LocalDate.of(2026, 11, 3));
            assertThat(l.statusDate()).isEqualTo(LocalDate.of(2026, 11, 20));
            assertThat(l.paidAmount()).isEqualByComparingTo("0");
            assertThat(l.statusReason()).isEqualTo("CO-50");
        });
    }

    @Test
    void fileWithoutLifecycleColumnsHasNone() {
        assertThat(mapper.map(HEADER + "\n" + LINE, "S").claims().get(0).lifecycle()).isEqualTo(SilverRecord.Lifecycle.NONE);
        // Present but blank = not known yet (e.g. a just-submitted claim has no amounts).
        SilverRecord.Lifecycle l = mapper.map(LIFECYCLE_HEADER + "\n" + LINE + ",60054,AETNA,SUBMITTED,2026-11-03,,,,,", "S")
                .claims().get(0).lifecycle();
        assertThat(l.status()).isEqualTo("SUBMITTED");
        assertThat(l.statusDate()).isNull();
        assertThat(l.paidAmount()).isNull();
    }

    @Test
    void badLifecycleValuesFail() {
        String ok = ",60054,AETNA,PAID,2026-11-03,2026-11-20,120.00,100.00,20.00,PR-1";
        assertThatThrownBy(() -> mapper.map(LIFECYCLE_HEADER + "\n" + LINE + ok.replace("PAID", "LOST"), "S"))
                .hasMessageContaining("claim_status 'LOST' is not one of");
        assertThatThrownBy(() -> mapper.map(LIFECYCLE_HEADER + "\n" + LINE + ok.replace("PR-1", "50"), "S"))
                .hasMessageContaining("status_reason '50'");
        assertThatThrownBy(() -> mapper.map(LIFECYCLE_HEADER + "\n" + LINE + ok.replace("2026-11-20", "2026-11-01"), "S"))
                .hasMessage("status_date 2026-11-01 is before submitted_date 2026-11-03");
        assertThatThrownBy(() -> mapper.map(LIFECYCLE_HEADER + "\n" + LINE + ok.replace("100.00", "-1"), "S"))
                .hasMessageContaining("paid_amount -1 is not a non-negative amount");
    }
}
