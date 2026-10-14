// Ruleset model: mirrors the server's immutable definitions (GET /api/v1/sdk/rules).
import type { EvaluationReason, JsonValue } from '../types.js';

export type Operator =
  | 'IN' | 'STARTS_WITH' | 'ENDS_WITH' | 'CONTAINS' | 'MATCHES'
  | 'LESS_THAN' | 'LESS_THAN_OR_EQUAL' | 'GREATER_THAN' | 'GREATER_THAN_OR_EQUAL'
  | 'SEMVER_EQUAL' | 'SEMVER_LESS_THAN' | 'SEMVER_GREATER_THAN'
  | 'BEFORE' | 'AFTER' | 'SEGMENT_MATCH';

export interface Clause {
  attribute: string | null;
  operator: Operator;
  values: string[];
  negate: boolean;
}

export interface Rollout {
  variations: { variation: number; weight: number }[];
  bucketBy?: string | null;
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

export interface Targeting {
  offVariation: number;
  prerequisites: { flagKey: string; variation: number }[];
  targets: { variation: number; values: string[] }[];
  rules: Rule[];
  fallthrough: VariationOrRollout | null;
}

export interface FlagDefinition {
  key: string;
  type: 'BOOLEAN' | 'STRING' | 'NUMBER' | 'JSON';
  variations: { value: JsonValue; name?: string | null }[];
  enabled: boolean;
  targeting: Targeting;
  salt: string;
  version: number;
}

export interface SegmentDefinition {
  key: string;
  included: string[];
  excluded: string[];
  rules: { clauses: Clause[] }[];
}

export interface Ruleset {
  version: number;
  flags: Record<string, FlagDefinition>;
  segments: Record<string, SegmentDefinition>;
}

export interface EvaluationResult {
  value: JsonValue | null;
  variation: number | null;
  reason: EvaluationReason;
}
