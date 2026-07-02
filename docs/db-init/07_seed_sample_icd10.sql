-- =============================================================================
-- 07_seed_sample_icd10.sql
--
-- Seeds a representative sample of ~20 ICD-10-CM codes spanning several
-- chapters so the Ontology Coverage dashboard and CODE-001 validation rule
-- have real data to work against out of the box. This is a curated sample,
-- not a full ICD-10-CM distribution — production deployments should load
-- the full CMS/NCHS release into this table via a scheduled ETL job.
-- =============================================================================

INSERT INTO ontology.icd10_codes
    (code, description, category, chapter, is_billable, effective_date)
VALUES
    ('B34.9',    'Viral infection, unspecified',                                                        'Certain infectious and parasitic diseases',        'Chapter I',    TRUE, '2015-10-01'),
    ('E11.9',    'Type 2 diabetes mellitus without complications',                                      'Endocrine, nutritional and metabolic diseases',    'Chapter IV',   TRUE, '2015-10-01'),
    ('E66.9',    'Obesity, unspecified',                                                                 'Endocrine, nutritional and metabolic diseases',    'Chapter IV',   TRUE, '2015-10-01'),
    ('E78.5',    'Hyperlipidemia, unspecified',                                                          'Endocrine, nutritional and metabolic diseases',    'Chapter IV',   TRUE, '2015-10-01'),
    ('F32.9',    'Major depressive disorder, single episode, unspecified',                               'Mental, Behavioral and Neurodevelopmental disorders', 'Chapter V', TRUE, '2015-10-01'),
    ('F41.1',    'Generalized anxiety disorder',                                                         'Mental, Behavioral and Neurodevelopmental disorders', 'Chapter V', TRUE, '2015-10-01'),
    ('G47.00',   'Insomnia, unspecified',                                                                'Diseases of the nervous system',                   'Chapter VI',   TRUE, '2015-10-01'),
    ('I10',      'Essential (primary) hypertension',                                                     'Diseases of the circulatory system',               'Chapter IX',   TRUE, '2015-10-01'),
    ('I25.10',   'Atherosclerotic heart disease of native coronary artery without angina pectoris',       'Diseases of the circulatory system',               'Chapter IX',   TRUE, '2015-10-01'),
    ('J06.9',    'Acute upper respiratory infection, unspecified',                                       'Diseases of the respiratory system',               'Chapter X',    TRUE, '2015-10-01'),
    ('J44.9',    'Chronic obstructive pulmonary disease, unspecified',                                   'Diseases of the respiratory system',               'Chapter X',    TRUE, '2015-10-01'),
    ('J45.909',  'Unspecified asthma, uncomplicated',                                                    'Diseases of the respiratory system',               'Chapter X',    TRUE, '2015-10-01'),
    ('K21.9',    'Gastro-esophageal reflux disease without esophagitis',                                 'Diseases of the digestive system',                 'Chapter XI',   TRUE, '2015-10-01'),
    ('M25.50',   'Pain in unspecified joint',                                                            'Diseases of the musculoskeletal system',           'Chapter XIII', TRUE, '2015-10-01'),
    ('M54.50',   'Low back pain, unspecified',                                                           'Diseases of the musculoskeletal system',           'Chapter XIII', TRUE, '2015-10-01'),
    ('N39.0',    'Urinary tract infection, site not specified',                                          'Diseases of the genitourinary system',             'Chapter XIV',  TRUE, '2015-10-01'),
    ('R05.9',    'Cough, unspecified',                                                                   'Symptoms, signs and abnormal clinical findings',   'Chapter XVIII',TRUE, '2015-10-01'),
    ('R51.9',    'Headache, unspecified',                                                                'Symptoms, signs and abnormal clinical findings',   'Chapter XVIII',TRUE, '2015-10-01'),
    ('S52.531A', 'Displaced fracture of lower end of right radius, initial encounter for closed fracture','Injury, poisoning and certain other consequences of external causes', 'Chapter XIX', TRUE, '2015-10-01'),
    ('Z00.00',   'Encounter for general adult medical examination without abnormal findings',            'Factors influencing health status and contact with health services', 'Chapter XXI', TRUE, '2015-10-01')
ON CONFLICT (code) DO NOTHING;
