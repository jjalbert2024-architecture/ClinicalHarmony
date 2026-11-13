// Response shapes of the api-gateway (/api/v1). Mirrors the gateway's Java records
// (read/*Queries, web/MetaController, downstream/ServiceHealthService) and, for proxied PMI calls,
// patient-index-service's DTOs — the gateway passes those through unchanged.

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  totalPages: number;
}

/** RFC 7807 problem, as every gateway error is shaped. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  correlationId?: string;
  service?: string;
  downstreamStatus?: number;
  requiredRole?: string;
  errors?: { parameter?: string; field?: string; message: string }[];
}

// ---- shell ---------------------------------------------------------------------------------------

export interface GatewayConfig {
  apiVersion: string;
  auth: {
    issuer: string;
    clientId: string;
    flow: string;
    pkce: string;
    authorizationEndpoint: string;
    tokenEndpoint: string;
    logoutEndpoint: string;
    scope: string;
    roles: { viewer: string; steward: string };
  };
}

export interface Me {
  username: string;
  name: string | null;
  subject: string;
  client: string;
  roles: string[];
  canAct: boolean;
  tokenExpiresAt: string;
}

export interface LastRun {
  id: number;
  status: string;
  startedAt: string;
  finishedAt: string | null;
}

export interface LayerSummary {
  bronzeByStatus: Record<string, number>;
  silver: Record<string, number>;
  mastersByStatus: Record<string, number>;
  fhirResources: number;
  fhirUsCoreCompliant: number;
  lineageRecords: number;
  openFindingsBySeverity: Record<string, number>;
  pendingReviews: number;
  lastMatchRun: LastRun | null;
}

export interface ServiceStatus {
  service: string;
  status: 'UP' | 'DOWN' | string;
  httpStatus: number | null;
  latencyMs: number;
  detail: string | null;
}

export interface HealthReport {
  status: 'UP' | 'DEGRADED' | string;
  checkedAt: string;
  degraded: string[];
  services: ServiceStatus[];
}

// ---- patients / PMI ------------------------------------------------------------------------------

export interface MasterSummary {
  id: number;
  uuid: string;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  matchStatus: 'PROVISIONAL' | 'MATCHED' | 'MERGED' | string;
  matchConfidenceScore: number | null;
  sourcePatientCount: number;
  active: boolean;
  sourceSystems: string[] | null;
  updatedAt: string;
}

export interface SourceRecord {
  silverPatientId: number;
  sourceSystem: string;
  sourcePatientId: string;
  mrn: string | null;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  addressLine1: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  phone: string | null;
  rawMessageId: number | null;
}

export interface MergeEvent {
  mergeId: number;
  survivorMasterId: number;
  absorbedMasterId: number;
  score: number | null;
  matchMethod: string;
  decidedBy: string;
  mergedAt: string;
  movedSilverPatients: number;
  unmergedAt: string | null;
  unmergedBy: string | null;
  unmergeReason: string | null;
}

export interface ResourceCount {
  resourceType: string;
  total: number;
  usCoreCompliant: number;
}

export interface MasterDetail {
  master: MasterSummary;
  mergedIntoMasterId: number | null;
  sources: SourceRecord[];
  merges: MergeEvent[];
  fhirResources: ResourceCount[];
  pendingReviews: number;
}

export type TimelineKind = 'CONDITION' | 'OBSERVATION' | 'MEDICATION';

export interface TimelineEvent {
  kind: TimelineKind;
  id: number;
  occurredAt: string | null;
  datePrecision: 'day' | 'instant';
  endedAt: string | null;
  codeSystem: string | null;
  code: string | null;
  display: string | null;
  codeKnown: boolean;
  status: string | null;
  value: string | null;
  sourceSystem: string;
  silverPatientId: number;
  rawMessageId: number | null;
  fhirResourceId: number | null;
  fhirReference: string | null;
  usCoreCompliant: boolean | null;
}

export interface Timeline {
  masterPatientId: number;
  eventCount: number;
  events: TimelineEvent[];
}

export interface FieldScore {
  field: string;
  outcome: string;
  weight: number;
  maxWeight: number;
  valueA?: string;
  valueB?: string;
  similarity?: number;
}

export interface MatchBreakdown {
  score: number;
  weightedScore?: number;
  totalWeight?: number;
  maxWeight?: number;
  method: string;
  deterministicRule: string | null;
  decision: string;
  fields: FieldScore[];
}

export interface MatchCandidate {
  id: number;
  runId: number;
  silverPatientAId: number;
  silverPatientBId: number;
  masterAId: number;
  masterBId: number;
  score: number;
  matchMethod: string;
  deterministicRule: string | null;
  breakdown: MatchBreakdown | null;
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'AUTO_MERGED' | 'SUPERSEDED' | string;
  mergeId: number | null;
  reviewedBy: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  createdAt: string;
}

export interface PmiPatientRecord {
  silverPatientId: number;
  masterPatientId: number | null;
  masterStatus: string | null;
  sourceSystem: string;
  sourcePatientId: string;
  mrn: string | null;
  firstName: string | null;
  lastName: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  addressLine1: string | null;
  city: string | null;
  state: string | null;
  postalCode: string | null;
  phone: string | null;
}

export interface CandidateView {
  candidate: MatchCandidate;
  recordA: PmiPatientRecord;
  recordB: PmiPatientRecord;
}

export interface MatchRun {
  id: number;
  status: string;
  triggeredBy: string;
  startedAt: string;
  finishedAt: string | null;
  provisionalMasters: number;
  pairsCompared: number;
  autoMerges: number;
  queuedForReview: number;
  confirmedSingletons: number;
  errorMessage: string | null;
}

export interface RunResult {
  run: MatchRun;
  autoMerges: { mergeId: number; survivorMasterId: number; absorbedMasterId: number; score: number }[];
  queuedCandidateIds: number[];
  confirmedMasterIds: number[];
  skipped: string[];
  silverPatientsWithoutMaster: number;
}

// ---- ontology ------------------------------------------------------------------------------------

export interface TerminologyCoverage {
  terminology: string;
  referenceSetSize: number;
  usages: number;
  distinctCodes: number;
  knownDistinctCodes: number;
  knownUsages: number;
  coveragePercent: number | null;
  openFindings: number;
  validationRule: string;
  topUnknownCodes: { code: string; usages: number }[];
}

export interface Coverage {
  terminologies: TerminologyCoverage[];
  mappings: { sourceSystem: string; targetSystem: string; mappingType: string; count: number }[];
  mappingCoverage: { usedTerminology: string; distinctUsedCodes: number; withMapping: number; mapsTo: string }[];
}

export interface CodeLookup {
  valid: boolean;
  code: string;
  display: string | null;
  category: string | null;
  reason: string | null;
}

export interface ConceptMapping {
  sourceSystem: string;
  sourceCode: string;
  targetSystem: string;
  targetCode: string;
  mappingType: string;
  notes: string | null;
}

// ---- validation ----------------------------------------------------------------------------------

export interface Bucket {
  key: string;
  label: string;
  category: string | null;
  open: number;
  total: number;
  lastSeen: string | null;
}

export interface ValidationSummary {
  total: number;
  open: number;
  resolved: number;
  lastValidatedAt: string | null;
  bySeverity: Bucket[];
  byRule: Bucket[];
  byEntityType: Bucket[];
}

export interface Finding {
  id: number;
  ruleCode: string;
  ruleName: string;
  ruleCategory: string;
  severity: string;
  entityType: string;
  entityId: number;
  patientId: number | null;
  message: string;
  badValue: string | null;
  suggestedValue: string | null;
  resolved: boolean;
  resolvedAt: string | null;
  validatedAt: string;
}

export interface Rule {
  code: string;
  name: string;
  description: string | null;
  category: string;
  defaultSeverity: string;
  active: boolean;
  evaluatedBy: string;
  toggleable: boolean;
  openFindings: number;
  updatedAt: string | null;
}

// ---- FHIR ----------------------------------------------------------------------------------------

export interface TypeCount {
  resourceType: string;
  total: number;
  usCoreCompliant: number;
}

export interface ResourceSummary {
  id: number;
  resourceType: string;
  fhirId: string;
  masterPatientId: number | null;
  usCoreCompliant: boolean;
  complianceStatus: string | null;
  errorCount: number | null;
  warningCount: number | null;
  profile: string | null;
  updatedAt: string;
}

export interface Violation {
  severity: string;
  location: string | null;
  message: string;
  rule: string | null;
  suggestedFix: string | null;
}

export interface ValidationOutcome {
  resourceType: string;
  resourceId: string | null;
  profile: string | null;
  compliant: boolean;
  errorCount: number;
  warningCount: number;
  violations: Violation[];
}

export interface ComplianceNotes {
  profile?: string;
  compliant?: boolean;
  validator?: string;
  status?: string;
  errorCount?: number;
  warningCount?: number;
  violations?: Violation[];
  [key: string]: unknown;
}

export interface ResourceDetail {
  summary: ResourceSummary;
  resource: Record<string, unknown>;
  complianceNotes: ComplianceNotes | null;
  lineage: LineageRecord[];
}

export interface CcdaConversion {
  documentId: string | null;
  documentTitle: string | null;
  resourceCount: number;
  compliantCount: number;
  allCompliant: boolean;
  sections: string[];
  notes: string[];
  resources: {
    fullUrl: string;
    resourceType: string;
    resourceId: string;
    sourceSection: string;
    validation: ValidationOutcome;
  }[];
  bundle: Record<string, unknown>;
}

// ---- lineage / Bronze ----------------------------------------------------------------------------

export interface LineageStep {
  stage: string;
  timestamp?: string;
  transformer?: string;
  input_hash?: string;
  output_hash?: string;
  ref?: string;
}

export interface LineageRecord {
  id: number;
  step: string;
  sourceSchema: string;
  sourceTable: string;
  sourceId: number | null;
  targetSchema: string;
  targetTable: string;
  targetId: number;
  transformedAt: string;
  details: { target?: string; steps?: LineageStep[]; [key: string]: unknown } | null;
}

export interface BronzeSummary {
  id: number;
  sourceSystem: string;
  messageType: string;
  messageFormat: string;
  processingStatus: string;
  payloadChecksum: string;
  payloadLength: number;
  errorMessage: string | null;
  receivedAt: string;
  processedAt: string | null;
}

export interface LineageGraph {
  target: string;
  targetId: number;
  records: LineageRecord[];
  bronzeMessages: BronzeSummary[];
}

export interface BronzeDetail {
  summary: BronzeSummary;
  rawPayload: string;
  silverRows: { table: string; id: number }[];
}

export interface StepCount {
  step: string;
  sourceSchema: string;
  targetTable: string;
  count: number;
  lastTransformedAt: string | null;
}
