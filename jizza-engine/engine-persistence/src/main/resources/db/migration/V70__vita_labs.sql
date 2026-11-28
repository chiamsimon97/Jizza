-- Vita Health, Phase 1b part 2: lab results, the lab normaliser's reference table, and checks
-- (spec 4.2, 5.4, 5.5, 5.6).
--
-- Every result keeps two things apart (spec 2.4): the lab flag, from what the lab printed, and the
-- target status, worked out when it is shown from the doctor's target, the person's own range or a
-- Vita default. No reference range is seeded anywhere: a lab's range is the one it printed.

-- The canonical tests (system reference, no user_id). Seeded with names, synonyms and printed units
-- only. A conversion is used only where it carries a source, and reads "needs verification" until
-- verified_on is set. "group" is a reserved word, so the column is test_group.
create table vita_lab_test (
  code varchar(40) primary key,
  name varchar(120) not null,
  synonyms text[] not null default '{}',
  canonical_unit varchar(40),
  unit_aliases text[] not null default '{}',
  display_decimals smallint not null default 0,
  conversions jsonb not null default '[]'::jsonb,
  test_group varchar(12) not null default 'other',
  sort_order integer not null default 0,
  constraint vita_lab_test_group_check check (test_group in
    ('diabetes', 'kidney', 'lipids', 'liver', 'thyroid', 'blood_count', 'vitamins', 'other')),
  constraint vita_lab_test_decimals_check check (display_decimals between 0 and 4)
);

-- One report: a confirmed lab-report document becomes one report and its results.
create table vita_lab_report (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  lab_name varchar(200),
  collected_on date,
  reported_on date,
  document_id uuid references vita_document(id) on delete set null,
  doctor_id uuid references vita_doctor(id) on delete set null,
  status varchar(10) not null default 'confirmed',
  notes varchar(1000),
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_lab_report_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_lab_report_status_check check (status in ('draft', 'confirmed'))
);
create index vita_lab_report_person_idx on vita_lab_report (user_id, person_id, collected_on);

-- One printed row. value_num and the ref_ bounds are in the printed unit; the _canonical columns
-- are filled only when the unit is the test's canonical unit or a conversion with a source applies,
-- and are stored unrounded. flag is the lab flag: 'critical' only when the lab printed it.
create table vita_lab_result (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  report_id uuid not null references vita_lab_report(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  sort_order integer not null default 0,
  section varchar(200),
  test_code varchar(40) references vita_lab_test(code) on delete set null,
  test_name_as_printed varchar(200) not null,
  value_as_printed varchar(60),
  value_num numeric(18, 6),
  value_text varchar(200),
  unit varchar(40),
  unit_canonical varchar(40),
  value_canonical numeric(24, 10),
  ref_low numeric(18, 6),
  ref_high numeric(18, 6),
  ref_low_inclusive boolean not null default true,
  ref_high_inclusive boolean not null default true,
  ref_text varchar(500),
  ref_low_canonical numeric(24, 10),
  ref_high_canonical numeric(24, 10),
  conversion jsonb,
  flag_as_printed varchar(20),
  flag varchar(8) not null default 'unknown',
  confidence numeric(3, 2),
  confirmed boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_lab_result_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_lab_result_flag_check check (flag in ('low', 'normal', 'high', 'critical', 'unknown')),
  constraint vita_lab_result_confidence_check check (confidence is null or confidence between 0 and 1)
);
create index vita_lab_result_report_idx on vita_lab_result (report_id, sort_order);
create index vita_lab_result_test_idx on vita_lab_result (user_id, person_id, test_code) where deleted_at is null;

-- Checks done (eye, foot, ...). A check that is a lab test takes "last done" from confirmed lab
-- results. next_due_on is a date the doctor gave; otherwise "due" comes only from an interval set
-- in config.
create table vita_check (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  condition_id uuid references vita_condition(id) on delete set null,
  kind varchar(10) not null,
  custom_name varchar(120),
  done_on date,
  result_note varchar(1000),
  visit_id uuid references vita_visit(id) on delete set null,
  document_id uuid references vita_document(id) on delete set null,
  next_due_on date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_check_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_check_kind_check check (kind in ('eye', 'foot', 'kidney', 'dental', 'lipids', 'hba1c', 'custom')),
  constraint vita_check_has_date check (done_on is not null or next_due_on is not null),
  constraint vita_check_custom_named check (kind <> 'custom' or custom_name is not null)
);
create index vita_check_person_idx on vita_check (user_id, person_id, kind);

-- The seed list of spec 5.4: names and synonyms only, no range. Canonical units are the units the
-- test is shown in; unit_aliases are other spellings of exactly the same unit (mg% is mg/dL).
insert into vita_lab_test (code, name, synonyms, canonical_unit, unit_aliases, display_decimals, test_group, sort_order) values
('hba1c', 'HbA1c', '{"HbA1c", "Glycosylated Haemoglobin", "Glycosylated Hemoglobin", "Glycated Haemoglobin", "Glycated Hemoglobin", "Glyco Hb", "A1c"}', '%', '{}', 1, 'diabetes', 10),
('fasting_glucose', 'Fasting glucose', '{"FBS", "Glucose Fasting", "Fasting Glucose", "Fasting Plasma Glucose", "FPG", "Fasting Blood Sugar", "Glucose, Fasting", "Plasma Glucose Fasting", "Blood Sugar Fasting"}', 'mg/dL', '{"mg%"}', 0, 'diabetes', 20),
('pp_glucose', 'After-food glucose', '{"PPBS", "Glucose PP", "2 hr PP", "2 hr Post Prandial", "Post Prandial Glucose", "Glucose Post Prandial", "Post Prandial Blood Sugar", "PP Blood Sugar"}', 'mg/dL', '{"mg%"}', 0, 'diabetes', 30),
('random_glucose', 'Random glucose', '{"RBS", "Random Blood Sugar", "Glucose Random", "Random Glucose", "Random Plasma Glucose"}', 'mg/dL', '{"mg%"}', 0, 'diabetes', 40),
('creatinine', 'Serum creatinine', '{"Serum Creatinine", "Creatinine", "S. Creatinine", "Creatinine, Serum"}', 'mg/dL', '{"mg%"}', 2, 'kidney', 110),
('urea', 'Blood urea', '{"Blood Urea", "Urea", "Serum Urea", "Urea, Serum"}', 'mg/dL', '{"mg%"}', 0, 'kidney', 120),
('bun', 'BUN', '{"BUN", "Blood Urea Nitrogen", "Urea Nitrogen"}', 'mg/dL', '{"mg%"}', 0, 'kidney', 130),
('uric_acid', 'Uric acid', '{"Uric Acid", "Serum Uric Acid", "Uric Acid, Serum"}', 'mg/dL', '{"mg%"}', 1, 'kidney', 140),
('egfr', 'eGFR', '{"eGFR", "GFR Estimated", "Estimated GFR", "GFR", "GFR, Estimated"}', 'mL/min/1.73m2', '{"ml/min/1.73 m2", "mL/min/1.73m²", "ml/min/1.73m^2"}', 0, 'kidney', 150),
('uacr', 'Urine albumin-creatinine ratio', '{"UACR", "Microalbumin/Creatinine", "Microalbumin Creatinine Ratio", "Urine Albumin Creatinine Ratio", "Albumin Creatinine Ratio", "ACR"}', 'mg/g', '{}', 0, 'kidney', 160),
('sodium', 'Sodium', '{"Sodium", "Serum Sodium", "Na", "Na+"}', 'mEq/L', '{}', 0, 'kidney', 170),
('potassium', 'Potassium', '{"Potassium", "Serum Potassium", "K", "K+"}', 'mEq/L', '{}', 1, 'kidney', 180),
('total_cholesterol', 'Total cholesterol', '{"Total Cholesterol", "Cholesterol, Total", "Cholesterol Total", "Cholesterol-Total", "Serum Cholesterol", "Cholesterol"}', 'mg/dL', '{"mg%"}', 0, 'lipids', 210),
('ldl', 'LDL cholesterol', '{"LDL", "LDL Cholesterol", "LDL Cholesterol, Calculated", "LDL Cholesterol Direct", "LDL-C"}', 'mg/dL', '{"mg%"}', 0, 'lipids', 220),
('hdl', 'HDL cholesterol', '{"HDL", "HDL Cholesterol", "HDL Cholesterol Direct", "HDL-C"}', 'mg/dL', '{"mg%"}', 0, 'lipids', 230),
('triglycerides', 'Triglycerides', '{"Triglycerides", "Triglyceride", "TG", "Serum Triglycerides"}', 'mg/dL', '{"mg%"}', 0, 'lipids', 240),
('vldl', 'VLDL cholesterol', '{"VLDL", "VLDL Cholesterol", "VLDL Cholesterol, Calculated"}', 'mg/dL', '{"mg%"}', 0, 'lipids', 250),
('non_hdl', 'Non-HDL cholesterol', '{"Non-HDL Cholesterol", "Non HDL Cholesterol", "Non-HDL"}', 'mg/dL', '{"mg%"}', 0, 'lipids', 260),
('sgpt', 'SGPT (ALT)', '{"SGPT", "ALT", "SGPT (ALT)", "ALT (SGPT)", "Alanine Aminotransferase", "Alanine Transaminase"}', 'U/L', '{"IU/L"}', 0, 'liver', 310),
('sgot', 'SGOT (AST)', '{"SGOT", "AST", "SGOT (AST)", "AST (SGOT)", "Aspartate Aminotransferase", "Aspartate Transaminase"}', 'U/L', '{"IU/L"}', 0, 'liver', 320),
('alp', 'Alkaline phosphatase', '{"Alkaline Phosphatase", "ALP", "Alk Phos", "Serum Alkaline Phosphatase"}', 'U/L', '{"IU/L"}', 0, 'liver', 330),
('total_bilirubin', 'Total bilirubin', '{"Total Bilirubin", "Bilirubin Total", "Bilirubin, Total", "Serum Bilirubin Total"}', 'mg/dL', '{"mg%"}', 1, 'liver', 340),
('albumin', 'Albumin', '{"Albumin", "Serum Albumin", "Albumin, Serum"}', 'g/dL', '{"gm%", "gm/dL", "g%"}', 1, 'liver', 350),
('total_protein', 'Total protein', '{"Total Protein", "Protein Total", "Protein, Total", "Serum Total Protein"}', 'g/dL', '{"gm%", "gm/dL", "g%"}', 1, 'liver', 360),
('ggt', 'GGT', '{"GGT", "Gamma GT", "Gamma Glutamyl Transferase", "GGTP"}', 'U/L', '{"IU/L"}', 0, 'liver', 370),
('tsh', 'TSH', '{"TSH", "Thyroid Stimulating Hormone", "TSH Ultrasensitive", "TSH (Ultrasensitive)"}', 'µIU/mL', '{"uIU/mL", "μIU/mL", "mIU/L"}', 2, 'thyroid', 410),
('t3', 'T3', '{"T3", "Total T3", "T3 Total", "Triiodothyronine"}', 'ng/dL', '{}', 0, 'thyroid', 420),
('t4', 'T4', '{"T4", "Total T4", "T4 Total", "Thyroxine"}', 'µg/dL', '{"ug/dL", "μg/dL", "mcg/dL"}', 1, 'thyroid', 430),
('free_t3', 'Free T3', '{"Free T3", "FT3", "Free Triiodothyronine"}', 'pg/mL', '{}', 2, 'thyroid', 440),
('free_t4', 'Free T4', '{"Free T4", "FT4", "Free Thyroxine"}', 'ng/dL', '{}', 2, 'thyroid', 450),
('haemoglobin', 'Haemoglobin', '{"Haemoglobin", "Hemoglobin", "Hb", "Haemoglobin (Hb)", "Hemoglobin (Hb)", "HGB"}', 'g/dL', '{"gm%", "gm/dL", "g%"}', 1, 'blood_count', 510),
('tlc', 'Total leucocyte count', '{"Total Leucocyte Count", "Total Leukocyte Count", "TLC", "WBC", "WBC Count", "Total WBC Count", "White Blood Cell Count"}', '/cumm', '{"/cu mm", "cells/cumm", "/mm3", "cells/mm3", "/µL", "/uL"}', 0, 'blood_count', 520),
('platelets', 'Platelet count', '{"Platelet Count", "Platelets", "PLT"}', '/cumm', '{"/cu mm", "cells/cumm", "/mm3", "cells/mm3", "/µL", "/uL"}', 0, 'blood_count', 530),
('rbc', 'RBC count', '{"RBC Count", "RBC", "Red Blood Cell Count", "Total RBC Count"}', 'mill/cumm', '{"million/cumm", "mill/cu mm", "million/µL", "million/uL"}', 2, 'blood_count', 540),
('pcv', 'PCV (haematocrit)', '{"PCV", "Haematocrit", "Hematocrit", "HCT", "Packed Cell Volume"}', '%', '{}', 1, 'blood_count', 550),
('mcv', 'MCV', '{"MCV", "Mean Corpuscular Volume"}', 'fL', '{"fl"}', 1, 'blood_count', 560),
('esr', 'ESR', '{"ESR", "Erythrocyte Sedimentation Rate"}', 'mm/hr', '{"mm/1st hr", "mm/1 hr", "mm/hour"}', 0, 'blood_count', 570),
('vitamin_d', 'Vitamin D (25-OH)', '{"Vitamin D", "Vitamin D (25-OH)", "25-OH Vitamin D", "25 Hydroxy Vitamin D", "Vitamin D Total", "Vitamin D3"}', 'ng/mL', '{}', 1, 'vitamins', 610),
('vitamin_b12', 'Vitamin B12', '{"Vitamin B12", "B12", "Cyanocobalamin", "Vitamin B-12"}', 'pg/mL', '{}', 0, 'vitamins', 620),
('crp', 'CRP', '{"CRP", "C-Reactive Protein", "C Reactive Protein", "hs-CRP", "CRP Quantitative"}', 'mg/L', '{}', 1, 'other', 710),
('calcium', 'Calcium', '{"Calcium", "Serum Calcium", "Calcium, Serum", "Total Calcium"}', 'mg/dL', '{"mg%"}', 1, 'other', 720);

-- The conversions spec 5.4 seeds, each with its source. verified_on stays null: they show "needs
-- verification" until someone checks them. Glucose and creatinine have none (no source recorded),
-- so a value printed in mmol/L or µmol/L is shown as printed and not converted.
update vita_lab_test set conversions = '[{"from": "mmol/L", "multiply": 38.67, "source": "Cholesterol mmol/L to mg/dL factor (spec 5.4)", "verifiedOn": null}]'::jsonb
 where code in ('total_cholesterol', 'ldl', 'hdl');
update vita_lab_test set conversions = '[{"from": "mmol/L", "multiply": 88.57, "source": "Triglycerides mmol/L to mg/dL factor (spec 5.4)", "verifiedOn": null}]'::jsonb
 where code = 'triglycerides';
update vita_lab_test set conversions = '[{"from": "mmol/mol", "multiply": 0.09148, "add": 2.152, "decimals": 1, "source": "NGSP master equation: NGSP % = 0.09148 × IFCC mmol/mol + 2.152 (spec 5.4)", "verifiedOn": null}]'::jsonb
 where code = 'hba1c';

-- Estimated average glucose from HbA1c (spec 5.5, ADAG): a secondary line, never instead of a
-- measured value. Needs verification like every clinical key.
insert into vita_config (key, scope, value, source_url) values
('clinical.formulas.eag', 'system', '{"from": "hba1c", "fromUnit": "%", "multiply": 28.7, "add": -46.7, "unit": "mg/dL", "decimals": 0}'::jsonb,
 'ADAG study: eAG mg/dL = 28.7 × HbA1c − 46.7 (spec 5.5)');
