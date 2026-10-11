// Mirrors the backend DTOs (see backend/src/main/java/.../*Dtos.java).

export type Role = 'VIEWER' | 'EDITOR' | 'ADMIN';
export type FlagType = 'BOOLEAN' | 'STRING' | 'NUMBER' | 'JSON';
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export type Operator =
  | 'IN' | 'STARTS_WITH' | 'ENDS_WITH' | 'CONTAINS' | 'MATCHES'
  | 'LESS_THAN' | 'LESS_THAN_OR_EQUAL' | 'GREATER_THAN' | 'GREATER_THAN_OR_EQUAL'
  | 'SEMVER_EQUAL' | 'SEMVER_LESS_THAN' | 'SEMVER_GREATER_THAN'
  | 'BEFORE' | 'AFTER' | 'SEGMENT_MATCH';

export interface User {
  id: number;
  email: string;
  displayName: string;
  role: Role;
  createdAt: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresAt: string;
  user: User;
}

export interface Environment {
  id: number;
  key: string;
  name: string;
  color: string;
  sortOrder: number;
  sdkKeyHint: string;
  createdAt: string;
}

export interface EnvironmentWithSdkKey {
  environment: Environment;
  sdkKey: string;
}

export interface Project {
  id: number;
  key: string;
  name: string;
  description: string | null;
  flagCount: number;
  environments: Environment[];
  createdAt: string;
  updatedAt: string;
}

export interface ProjectCreated {
  project: Project;
  environments: EnvironmentWithSdkKey[];
}

export interface Variation {
  value: JsonValue;
  name?: string | null;
  description?: string | null;
}

export interface Clause {
  attribute: string | null;
  operator: Operator;
  values: string[];
  negate: boolean;
}

export interface WeightedVariation {
  variation: number;
  weight: number;
}

export interface Rollout {
  variations: WeightedVariation[];
  bucketBy?: string;
}

export interface VariationOrRollout {
  variation?: number | null;
  rollout?: Rollout | null;
}

export interface Rule {
  id?: string | null;
  description?: string | null;
  clauses: Clause[];
  serve: VariationOrRollout;
}

export interface Target {
  variation: number;
  values: string[];
}

export interface Prerequisite {
  flagKey: string;
  variation: number;
}

export interface Targeting {
  offVariation: number;
  prerequisites: Prerequisite[];
  targets: Target[];
  rules: Rule[];
  fallthrough: VariationOrRollout;
}

export type FlagLifecycle = 'NEW' | 'ACTIVE' | 'POTENTIALLY_STALE' | 'PERMANENT' | 'ARCHIVED';

export interface EnvironmentState {
  enabled: boolean;
  version: number;
  summary: string;
  updatedAt: string;
}

export interface FlagSummary {
  key: string;
  name: string;
  description: string | null;
  type: FlagType;
  variations: Variation[];
  tags: string[];
  temporary: boolean;
  archived: boolean;
  lifecycle: FlagLifecycle;
  environments: Record<string, EnvironmentState>;
  createdAt: string;
  updatedAt: string;
}

export interface FlagUsage {
  total: number;
  byVariation: Record<string, number>;
  lastEvaluatedAt: string | null;
}

export interface FlagConfig {
  environmentKey: string;
  environmentName: string;
  enabled: boolean;
  targeting: Targeting;
  version: number;
  updatedBy: string | null;
  updatedAt: string;
  usage: FlagUsage;
}

export interface Flag {
  key: string;
  name: string;
  description: string | null;
  type: FlagType;
  variations: Variation[];
  defaultOnVariation: number;
  defaultOffVariation: number;
  tags: string[];
  temporary: boolean;
  archived: boolean;
  lifecycle: FlagLifecycle;
  version: number;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  environments: FlagConfig[];
  dependentFlags: string[];
}

export interface SegmentRule {
  clauses: Clause[];
}

export interface Segment {
  key: string;
  name: string;
  description: string | null;
  included: string[];
  excluded: string[];
  rules: SegmentRule[];
  version: number;
  usedByFlags: string[];
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AuditEntry {
  id: number;
  projectKey: string | null;
  environmentKey: string | null;
  resourceType: 'PROJECT' | 'ENVIRONMENT' | 'FLAG' | 'FLAG_CONFIG' | 'SEGMENT' | 'USER';
  resourceKey: string;
  action: string;
  actor: string;
  summary: string;
  before: JsonValue | null;
  after: JsonValue | null;
  createdAt: string;
}

export type ReasonKind = 'OFF' | 'TARGET_MATCH' | 'RULE_MATCH' | 'FALLTHROUGH' | 'PREREQUISITE_FAILED' | 'ERROR';

export interface EvaluationReason {
  kind: ReasonKind;
  ruleIndex?: number;
  ruleId?: string;
  prerequisiteKey?: string;
  inRollout?: boolean;
  errorKind?: string;
}

export interface FlagValue {
  value?: JsonValue;
  variation?: number;
  reason: EvaluationReason;
  version: number;
}

export interface EvaluationResponse {
  version: number;
  flags: Record<string, FlagValue>;
}
