package com.clinicalharmony.ingestion.service;

/**
 * Sample HL7 v2.5 payloads for tests. Defined as Java string literals (not loaded from
 * resource files) so the required \r segment terminators can't be silently rewritten by
 * an editor's line-ending normalization.
 */
public final class Hl7SampleMessages {

    public static final String ADT_A01 =
            "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260702101500||ADT^A01^ADT_A01|MSG00001|P|2.5\r"
            + "EVN|A01|20260702101500\r"
            + "PID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F|||123 MAIN ST^^SPRINGFIELD^IL^62704||5551234567\r"
            + "PV1|1|I|2000^2012^01||||1234^ATTEND^PHYSICIAN|||SUR||||ADM|A0\r";

    public static final String ORU_R01 =
            "MSH|^~\\&|LAB_SYSTEM|REGIONAL_LAB|CLINICALHARMONY|INGESTION|20260702110000||ORU^R01^ORU_R01|MSG00002|P|2.5\r"
            + "PID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F\r"
            + "OBR|1|LABORD1001|LABFIL2002|24331-1^Lipid Panel^LN|||20260702103000\r"
            + "OBX|1|NM|2093-3^Cholesterol^LN||185|mg/dL|<200|N|||F\r"
            + "OBX|2|NM|2571-8^Triglycerides^LN||140|mg/dL|<150|N|||F\r";

    public static final String MALFORMED = "THIS IS NOT A VALID HL7 MESSAGE\r";

    private Hl7SampleMessages() {
    }
}
