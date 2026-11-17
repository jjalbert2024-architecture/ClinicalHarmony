"""Synthetic reference data. Codes are taken from the terminology subsets seeded in docs/db-init
(07 ICD-10, 08 LOINC, 09 RxNorm), so the 9b Silver layer can validate them against ontology-service.
Names and addresses are invented; no real person is represented."""

# (sending application, sending facility / source system, message families it sends)
FACILITIES = [
    ("REG_SYSTEM", "CITY_HOSPITAL", ("ADT",)),
    ("EHR_NC", "NORTH_CLINIC", ("ADT", "ORU")),
    ("LAB_SYSTEM", "REGIONAL_LAB", ("ORU",)),
    # Pharmacy orders (9b). The patient's MRN keeps its home facility as PID-3.4 assigning authority,
    # so the Silver layer attaches the order to that facility's patient, not to a "pharmacy patient".
    ("RX_SYSTEM", "CITY_PHARMACY", ("RDE",)),
]
ADT_FACILITIES = [f for f in FACILITIES if "ADT" in f[2]]
ORU_FACILITIES = [f for f in FACILITIES if "ORU" in f[2]]

FIRST_NAMES_F = ["MARIA", "AISHA", "EMILY", "PRIYA", "SOFIA", "GRACE", "HANNAH", "OLIVIA", "CHLOE", "NORA",
                 "LEILA", "MEI", "ROSA", "ZOE", "IRENE"]
FIRST_NAMES_M = ["JAMES", "RAHUL", "DANIEL", "OMAR", "LUCAS", "ETHAN", "SAMUEL", "LIAM", "NOAH", "VICTOR",
                 "HUGO", "KENJI", "MATEO", "ARJUN", "FELIX"]
LAST_NAMES = ["GARCIA", "NGUYEN", "PATEL", "JOHNSON", "OKAFOR", "MULLER", "ROSSI", "KOWALSKI", "SATO", "HASSAN",
              "BROWN", "SILVA", "IVANOV", "KIM", "DUBOIS", "MARTIN", "LOPEZ", "SINGH", "COHEN", "WALKER"]
STREETS = ["MAPLE AVE", "OAK ST", "CEDAR LN", "PINE RD", "ELM ST", "BIRCH WAY", "LAKE DR", "HILL ST"]
CITIES = [("SPRINGFIELD", "IL", "627"), ("RIVERTON", "WY", "825"), ("FAIRVIEW", "OR", "970"),
          ("GREENVILLE", "SC", "296"), ("MADISON", "WI", "537")]

DOCTORS = [("1001", "SHAH", "ANIL"), ("1002", "BERG", "LENA"), ("1003", "OSEI", "KWAME"), ("1004", "LIND", "ERIK")]

# ICD-10-CM (docs/db-init/07)
DIAGNOSES = [
    ("E11.9", "Type 2 diabetes mellitus without complications"),
    ("I10", "Essential (primary) hypertension"),
    ("E78.5", "Hyperlipidemia, unspecified"),
    ("J45.909", "Unspecified asthma, uncomplicated"),
    ("J44.9", "Chronic obstructive pulmonary disease, unspecified"),
    ("K21.9", "Gastro-esophageal reflux disease without esophagitis"),
    ("N39.0", "Urinary tract infection, site not specified"),
    ("J06.9", "Acute upper respiratory infection, unspecified"),
    ("M54.50", "Low back pain, unspecified"),
    ("F41.1", "Generalized anxiety disorder"),
]

# RxNorm ingredients (docs/db-init/09) for AL1 allergies. Every allergen is also in MEDICATIONS, so a
# pharmacy order can (deliberately) conflict with the allergy → MEDICATION_ALLERGY_CONFLICT in 9b.
ALLERGENS = [("723", "Amoxicillin"), ("5640", "Ibuprofen"), ("6809", "Metformin")]
REACTIONS = ["RASH", "HIVES", "NAUSEA", "SWELLING"]

# RxNorm ingredients (docs/db-init/09) for RDE^O11 pharmacy orders:
# rxcui → (display, give amount, units, administration instructions)
MEDICATIONS = {
    "6809": ("Metformin 500 MG Oral Tablet", "500", "mg", "Take 1 tablet by mouth twice daily with meals"),
    "29046": ("Lisinopril 10 MG Oral Tablet", "10", "mg", "Take 1 tablet by mouth once daily"),
    "17767": ("Amlodipine 5 MG Oral Tablet", "5", "mg", "Take 1 tablet by mouth once daily"),
    "83367": ("Atorvastatin 20 MG Oral Tablet", "20", "mg", "Take 1 tablet by mouth at bedtime"),
    "7646": ("Omeprazole 20 MG Delayed Release Oral Capsule", "20", "mg", "Take 1 capsule by mouth before breakfast"),
    "5640": ("Ibuprofen 400 MG Oral Tablet", "400", "mg", "Take 1 tablet by mouth every 6 hours as needed for pain"),
    "723": ("Amoxicillin 500 MG Oral Capsule", "500", "mg", "Take 1 capsule by mouth three times daily for 7 days"),
    "161": ("Acetaminophen 500 MG Oral Tablet", "500", "mg", "Take 2 tablets by mouth every 6 hours as needed"),
    "36437": ("Sertraline 50 MG Oral Tablet", "50", "mg", "Take 1 tablet by mouth once daily"),
}

# What a prescriber would typically order for each diagnosis (ICD-10 → RxNorm ingredients above)
TREATMENTS = {
    "E11.9": ["6809"],
    "I10": ["29046", "17767"],
    "E78.5": ["83367"],
    "K21.9": ["7646"],
    "N39.0": ["723"],
    "J06.9": ["161"],
    "M54.50": ["5640", "161"],
    "F41.1": ["36437"],
}

# LOINC (docs/db-init/08): code, name, unit, low, high, decimals
LABS = {
    "2345-7": ("Glucose", "mg/dL", 70, 99, 0),
    "4548-4": ("Hemoglobin A1c/Hemoglobin.total", "%", 4.0, 5.6, 1),
    "2160-0": ("Creatinine", "mg/dL", 0.6, 1.3, 2),
    "718-7": ("Hemoglobin", "g/dL", 12.0, 17.5, 1),
    "777-3": ("Platelets", "10*3/uL", 150, 400, 0),
    "6690-2": ("Leukocytes", "10*3/uL", 4.0, 11.0, 1),
    "2093-3": ("Cholesterol", "mg/dL", 125, 200, 0),
    "2571-8": ("Triglyceride", "mg/dL", 40, 150, 0),
    "2085-9": ("Cholesterol in HDL", "mg/dL", 40, 90, 0),
    "13457-7": ("Cholesterol in LDL", "mg/dL", 50, 100, 0),
    "2951-2": ("Sodium", "mmol/L", 135, 145, 0),
    "2823-3": ("Potassium", "mmol/L", 3.5, 5.1, 1),
}

# OBR universal service id (LOINC panel) → member tests
PANELS = [
    ("24331-1", "Lipid panel", ["2093-3", "2571-8", "2085-9", "13457-7"]),
    ("58410-2", "CBC panel", ["718-7", "6690-2", "777-3"]),
    ("24323-8", "Comprehensive metabolic panel", ["2345-7", "2951-2", "2823-3", "2160-0"]),
    ("4548-4", "Hemoglobin A1c", ["4548-4"]),
]

MALFORMED_KINDS = [
    "MISSING_MSH",         # MSH segment dropped: no header to parse at all
    "NOT_HL7",             # free text sent to the HL7 port
    "EMPTY_CONTROL_ID",    # MSH-10 blank: nothing to ACK or trace
    "EMPTY_MESSAGE_TYPE",  # MSH-9 blank: can't tell ADT from ORU
    "TRUNCATED_HEADER",    # connection dropped mid-MSH by a broken interface engine
]
