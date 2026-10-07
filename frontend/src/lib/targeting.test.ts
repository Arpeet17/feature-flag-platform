import { describe, expect, it } from 'vitest';
import type { Targeting } from '../api/types';
import {
  TOTAL_WEIGHT, describeServe, evenRollout, formatPercent, parseVariationValue, percentToWeight, rolloutTotal,
  validateTargeting, weightToPercent,
} from './targeting';

const variations = [{ value: true, name: 'On' }, { value: false, name: 'Off' }];

describe('rollout weights', () => {
  it('converts between percent and weight without float drift', () => {
    expect(percentToWeight(33.333)).toBe(33_333);
    expect(percentToWeight(0.1 + 0.2)).toBe(300);
    expect(weightToPercent(12_345)).toBe(12.345);
    expect(formatPercent(50_000)).toBe('50%');
    expect(formatPercent(333)).toBe('0.333%');
  });

  it('splits evenly and always totals exactly 100%', () => {
    for (const n of [2, 3, 6, 7]) {
      const rollout = evenRollout(n);
      expect(rollout.variations).toHaveLength(n);
      expect(rolloutTotal(rollout)).toBe(TOTAL_WEIGHT);
    }
    expect(evenRollout(3).variations.map((v) => v.weight)).toEqual([33_334, 33_333, 33_333]);
  });

  it('describes what is served', () => {
    expect(describeServe({ variation: 0 }, variations)).toBe('On');
    expect(describeServe({ rollout: { variations: [{ variation: 0, weight: 25_000 }, { variation: 1, weight: 75_000 }] } }, variations))
      .toBe('On 25% / Off 75%');
  });
});

describe('validateTargeting', () => {
  const base: Targeting = { offVariation: 1, prerequisites: [], targets: [], rules: [], fallthrough: { variation: 0 } };

  it('accepts a valid configuration', () => {
    expect(validateTargeting(base, 2)).toEqual([]);
  });

  it('flags incomplete rules, bad rollouts and duplicate targets', () => {
    const errors = validateTargeting({
      ...base,
      targets: [{ variation: 0, values: ['a'] }, { variation: 1, values: ['a'] }],
      rules: [
        { clauses: [], serve: { variation: 0 } },
        { clauses: [{ attribute: '', operator: 'IN', values: [], negate: false }], serve: { variation: 0 } },
      ],
      fallthrough: { rollout: { variations: [{ variation: 0, weight: 40_000 }] } },
    }, 2);
    expect(errors).toEqual([
      'Rule 1: add at least one condition',
      'Rule 2, condition 1: add at least one value',
      'Rule 2, condition 1: attribute is required',
      'Default rule: rollout adds up to 40%, must be 100%',
      '"a" is individually targeted more than once',
    ]);
  });

  it('does not require an attribute for segment clauses', () => {
    const errors = validateTargeting({
      ...base,
      rules: [{ clauses: [{ attribute: null, operator: 'SEGMENT_MATCH', values: ['beta'], negate: false }], serve: { variation: 0 } }],
    }, 2);
    expect(errors).toEqual([]);
  });
});

describe('parseVariationValue', () => {
  it.each([
    ['BOOLEAN', 'true', true],
    ['NUMBER', '42.5', 42.5],
    ['STRING', 'hello', 'hello'],
    ['JSON', '{"a":1}', { a: 1 }],
  ] as const)('parses %s %s', (type, raw, expected) => {
    expect(parseVariationValue(type, raw)).toEqual({ value: expected });
  });

  it.each([
    ['BOOLEAN', 'yes'],
    ['NUMBER', 'abc'],
    ['NUMBER', ''],
    ['JSON', '"just a string"'],
    ['JSON', '{broken'],
  ] as const)('rejects %s %s', (type, raw) => {
    expect(parseVariationValue(type, raw).error).toBeDefined();
  });
});
