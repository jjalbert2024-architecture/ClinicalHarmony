package com.clinicalharmony.silver.hl7;

/** HL7 v2.5 messages in exactly the shape data-generator sends (segment terminator \r). */
public final class Hl7Samples {

    public static final String PID = "PID|1||CH645175^^^CITY_HOSPITAL^MR||SINGH^JAMES^D||19350225|M|||5977 LAKE DR^^GREENVILLE^SC^29608||5555539837";

    public static String adtA01(String controlId) {
        return String.join("\r",
                "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20261102073451||ADT^A01^ADT_A01|" + controlId + "|P|2.5",
                "EVN|A01|20261102073451",
                PID,
                "PV1|1|I|CARD^227^1||||1001^SHAH^ANIL|||MED||||ADM|A0",
                "DG1|1||K21.9^Gastro-esophageal reflux disease without esophagitis^I10||20261102073451|A",
                "DG1|2||I10^Essential (primary) hypertension^I10||20261102073451|A",
                "AL1|1|DA|723^Amoxicillin^RXNORM|SV|HIVES") + "\r";
    }

    /** Same patient: new phone, a repeated diagnosis (I10) and a new one (E11.9), the same allergy again. */
    public static String adtA08(String controlId) {
        return String.join("\r",
                "MSH|^~\\&|REG_SYSTEM|CITY_HOSPITAL|CLINICALHARMONY|INGESTION|20261103090000||ADT^A08^ADT_A01|" + controlId + "|P|2.5",
                "EVN|A08|20261103090000",
                PID.replace("5555539837", "5550001111"),
                "PV1|1|O|MED^101^1||||1002^BERG^LENA|||MED||||REF|A0",
                "DG1|1||I10^Essential (primary) hypertension^I10||20261103090000|W",
                "DG1|2||E11.9^Type 2 diabetes mellitus without complications^I10||20261103090000|W",
                "AL1|1|DA|723^Amoxicillin^RXNORM|SV|HIVES") + "\r";
    }

    public static String oruR01(String controlId) {
        return String.join("\r",
                "MSH|^~\\&|LAB_SYSTEM|REGIONAL_LAB|CLINICALHARMONY|INGESTION|20261102083655||ORU^R01^ORU_R01|" + controlId + "|P|2.5",
                PID,
                "OBR|1|ORD907189|FIL907189|24323-8^Comprehensive metabolic panel^LN|||20261102062755|||||||||1001^SHAH^ANIL||||||20261102083655|||F",
                "OBX|1|NM|2345-7^Glucose^LN||182|mg/dL|70-99|H|||F|||20261102062000",
                "OBX|2|NM|2951-2^Sodium^LN||140|mmol/L|135-145|N|||F",
                "OBX|3|ST|2823-3^Potassium^LN||hemolyzed|mmol/L|3.5-5.1||||F") + "\r";
    }

    /** Amoxicillin order for a patient allergic to amoxicillin: MEDICATION_ALLERGY_CONFLICT. */
    public static String rdeO11(String controlId) {
        return String.join("\r",
                "MSH|^~\\&|RX_SYSTEM|CITY_PHARMACY|CLINICALHARMONY|INGESTION|20261102074359||RDE^O11^RDE_O11|" + controlId + "|P|2.5",
                PID,
                "ORC|NW|RX891937|PH891937||||||20261102074359|||1003^OSEI^KWAME",
                "RXE|^^^20261102074359^^R|723^Amoxicillin 500 MG Oral Capsule^RXNORM|500||mg^mg^UCUM||^Take 1 capsule by mouth three times daily for 7 days|||30|TAB",
                "RXR|PO^Oral^HL70162") + "\r";
    }

    private Hl7Samples() {
    }
}
