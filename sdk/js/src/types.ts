export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export interface EvaluationContext {
  /** Stable, unique identifier (user id, device id, ...). Drives targeting and rollout bucketing. */
  key: string;
  attributes?: Record<string, JsonValue>;
}

export interface EvaluationReason {
  kind: 'OFF' | 'TARGET_MATCH' | 'RULE_MATCH' | 'FALLTHROUGH' | 'PREREQUISITE_FAILED' | 'ERROR';
  ruleIndex?: number;
  ruleId?: string;
  prerequisiteKey?: string;
  inRollout?: boolean;
  errorKind?: 'FLAG_NOT_FOUND' | 'MALFORMED_FLAG' | 'WRONG_TYPE' | 'CLIENT_NOT_READY';
}

export interface FlagValue {
  value?: JsonValue;
  variation?: number;
  reason: EvaluationReason;
  version: number;
}

export interface EvaluationDetail<T> {
  value: T;
  variation: number | null;
  reason: EvaluationReason;
}

export interface FlagClientOptions {
  /** Base URL of the platform, e.g. https://flags.example.com */
  baseUrl: string;
  /** Environment SDK key (keep it server-side). */
  sdkKey: string;
  /** Subscribe to real-time updates over SSE. Default true. */
  streaming?: boolean;
  /** Initial reconnect delay in ms; grows exponentially with jitter up to maxReconnectDelayMs. */
  reconnectDelayMs?: number;
  maxReconnectDelayMs?: number;
  /** Injectable for tests and non-standard runtimes. */
  fetch?: typeof fetch;
  logger?: Pick<Console, 'warn' | 'debug'>;
}

export interface ChangeEvent {
  /** Keys whose value or variation changed for the current context. */
  changed: string[];
}

export interface ClientEvents {
  ready: void;
  change: ChangeEvent;
  error: Error;
}
