package com.clinicalharmony.bronze.hl7;

/** HL7 v2.5 test payloads as Java literals so the \r segment terminators survive editors. */
public final class Hl7Samples {

    public static final String ADT_A01 =
            "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260702101500||ADT^A01^ADT_A01|MSG00001|P|2.5\r"
            + "EVN|A01|20260702101500\r"
            + "PID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F|||123 MAIN ST^^SPRINGFIELD^IL^62704||5551234567\r"
            + "PV1|1|I|2000^2012^01||||1234^ATTEND^PHYSICIAN|||SUR||||ADM|A0\r";

    public static final String ORU_R01 =
            "MSH|^~\\&|LAB_SYSTEM|REGIONAL_LAB|CLINICALHARMONY|INGESTION|20260702110000||ORU^R01^ORU_R01|MSG00002|P|2.5\r"
            + "PID|1||MRN100234^^^CITY_HOSPITAL^MR||DOE^JANE^A||19800515|F\r"
            + "OBR|1|LABORD1001|LABFIL2002|24331-1^Lipid Panel^LN|||20260702103000\r"
            + "OBX|1|NM|2093-3^Cholesterol^LN||185|mg/dL|<200|N|||F\r";

    public static final String NOT_HL7 = "THIS IS NOT A VALID HL7 MESSAGE\r";

    public static final String NO_CONTROL_ID =
            "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260702101500||ADT^A01^ADT_A01||P|2.5\r"
            + "PID|1||MRN1^^^CITY_HOSPITAL^MR||DOE^JOHN\r";

    public static final String NO_MESSAGE_TYPE =
            "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20260702101500|||MSG9|P|2.5\r"
            + "PID|1||MRN1^^^CITY_HOSPITAL^MR||DOE^JOHN\r";

    private Hl7Samples() {
    }
}
