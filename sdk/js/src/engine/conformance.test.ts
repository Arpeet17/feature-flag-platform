import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { EvaluationContext } from '../types.js';
import { matchesClause } from './clauses.js';
import { Evaluator } from './evaluator.js';
import type { Clause, Ruleset } from './model.js';
import { bucket, murmur3 } from './murmur3.js';

/**
 * The same vectors the Java engine is tested against (backend ConformanceVectorsTest).
 * If SDK and server ever disagree on a single user's variation, one of the builds fails.
 */
const vectors = JSON.parse(readFileSync(fileURLToPath(new URL('../../../../conformance/evaluation-vectors.json', import.meta.url)), 'utf8'));
const evaluator = new Evaluator({ version: 0, flags: vectors.flags, segments: vectors.segments } as Ruleset);

describe('conformance: murmur3', () => {
  it.each(vectors.murmur3 as { input: string; seed: number; expected: number }[])('"$input" seed $seed', (c) => {
    expect(murmur3(c.input, c.seed)).toBe(c.expected);
  });
});

describe('conformance: buckets', () => {
  it.each(vectors.buckets as { flagKey: string; salt: string; value: string; expected: number }[])('$flagKey / $value', (c) => {
    expect(bucket(c.flagKey, c.salt, c.value)).toBe(c.expected);
  });
});

describe('conformance: clauses', () => {
  it.each(vectors.clauses as { description: string; clause: Clause; context: EvaluationContext; expected: boolean }[])('$description', (c) => {
    expect(matchesClause(c.clause, c.context, null)).toBe(c.expected);
  });
});

describe('conformance: evaluations', () => {
  it.each(vectors.evaluations as { description: string; flag: string; context: EvaluationContext; expected: unknown }[])('$description', (c) => {
    expect(evaluator.evaluate(c.flag, c.context)).toStrictEqual(c.expected);
  });
});
