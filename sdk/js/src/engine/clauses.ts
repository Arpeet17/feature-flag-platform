import type { EvaluationContext } from '../types.js';
import type { Clause, Operator } from './model.js';
import { compareSemver, parseSemver } from './semver.js';

/**
 * Clause matching with the server's exact semantics (see conformance/evaluation-vectors.json):
 * type mismatches never throw and never match; a missing attribute never matches, even when
 * negated; array attributes match if any element does.
 */
export function attribute(context: EvaluationContext, name: string | null): unknown {
  if (name === 'key') return context.key;
  const value = name == null ? undefined : context.attributes?.[name];
  return value === null ? undefined : value;
}

export function matchesClause(clause: Clause, context: EvaluationContext, segmentMatches: ((key: string) => boolean) | null): boolean {
  let result: boolean;
  if (clause.operator === 'SEGMENT_MATCH') {
    result = segmentMatches !== null && clause.values.some(segmentMatches);
  } else {
    const actual = attribute(context, clause.attribute);
    if (actual === undefined) return false;
    result = Array.isArray(actual)
      ? actual.some((v) => v != null && matchesAny(clause, v))
      : matchesAny(clause, actual);
  }
  return clause.negate !== result;
}

function matchesAny(clause: Clause, actual: unknown): boolean {
  return clause.values.some((expected) => matchesValue(clause.operator, actual, expected));
}

function matchesValue(op: Operator, actual: unknown, expected: string): boolean {
  switch (op) {
    case 'IN': return equalsLoosely(actual, expected);
    case 'STARTS_WITH': return typeof actual === 'string' && actual.startsWith(expected);
    case 'ENDS_WITH': return typeof actual === 'string' && actual.endsWith(expected);
    case 'CONTAINS': return typeof actual === 'string' && actual.includes(expected);
    case 'MATCHES': return typeof actual === 'string' && (regex(expected)?.test(actual) ?? false);
    case 'LESS_THAN': return cmp(compareNumbers(actual, expected), (c) => c < 0);
    case 'LESS_THAN_OR_EQUAL': return cmp(compareNumbers(actual, expected), (c) => c <= 0);
    case 'GREATER_THAN': return cmp(compareNumbers(actual, expected), (c) => c > 0);
    case 'GREATER_THAN_OR_EQUAL': return cmp(compareNumbers(actual, expected), (c) => c >= 0);
    case 'SEMVER_EQUAL': return cmp(compareVersions(actual, expected), (c) => c === 0);
    case 'SEMVER_LESS_THAN': return cmp(compareVersions(actual, expected), (c) => c < 0);
    case 'SEMVER_GREATER_THAN': return cmp(compareVersions(actual, expected), (c) => c > 0);
    case 'BEFORE': return cmp(compareDates(actual, expected), (c) => c < 0);
    case 'AFTER': return cmp(compareDates(actual, expected), (c) => c > 0);
    case 'SEGMENT_MATCH': return false;
  }
}

const cmp = (c: number | null, test: (c: number) => boolean) => c !== null && test(c);

function equalsLoosely(actual: unknown, expected: string): boolean {
  if (typeof actual === 'number') return compareNumbers(actual, expected) === 0;
  return String(actual) === expected;
}

/** Same grammar as java.math.BigDecimal(String): no hex, no Infinity/NaN, optional sign/exponent. */
const DECIMAL = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;

function toNumber(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value === 'string') {
    const s = value.trim();
    return DECIMAL.test(s) ? Number(s) : null;
  }
  return null;
}

function compareNumbers(actual: unknown, expected: string): number | null {
  const a = toNumber(actual);
  const e = toNumber(expected);
  return a === null || e === null ? null : Math.sign(a - e);
}

function compareVersions(actual: unknown, expected: string): number | null {
  if (typeof actual !== 'string') return null;
  const a = parseSemver(actual);
  const e = parseSemver(expected);
  return a && e ? compareSemver(a, e) : null;
}

/** ISO-8601 with a mandatory offset (like java.time.OffsetDateTime), or epoch milliseconds. */
const ISO_OFFSET = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;

export function toEpochMillis(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? Math.trunc(value) : null;
  if (typeof value !== 'string') return null;
  const s = value.trim();
  if (ISO_OFFSET.test(s)) {
    // Date.parse keeps millisecond precision; compare sub-millisecond digits separately.
    const ms = Date.parse(s.replace(/(\.\d{3})\d+/, '$1'));
    return Number.isNaN(ms) ? null : ms;
  }
  return /^[+-]?\d+$/.test(s) ? Number(s) : null;
}

function compareDates(actual: unknown, expected: string): number | null {
  const a = toEpochMillis(actual);
  const e = toEpochMillis(expected);
  return a === null || e === null ? null : Math.sign(a - e);
}

const MAX_CACHED_PATTERNS = 1_000;
const patterns = new Map<string, RegExp | null>();

function regex(source: string): RegExp | null {
  let re = patterns.get(source);
  if (re === undefined) {
    if (patterns.size > MAX_CACHED_PATTERNS) patterns.clear();
    try {
      re = new RegExp(source, 'u');
    } catch {
      re = null;
    }
    patterns.set(source, re);
  }
  return re;
}
