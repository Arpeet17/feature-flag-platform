import type { Clause, FlagType, JsonValue, Operator, Rollout, Targeting, Variation, VariationOrRollout } from '../api/types';

/** Rollout weights are in thousandths of a percent: 100_000 = 100%. */
export const TOTAL_WEIGHT = 100_000;

export const weightToPercent = (weight: number) => Math.round(weight) / 1000;
export const percentToWeight = (percent: number) => Math.round(percent * 1000);

export function formatPercent(weight: number) {
  return `${Number(weightToPercent(weight).toFixed(3))}%`;
}

export const OPERATORS: { value: Operator; label: string; hint?: string }[] = [
  { value: 'IN', label: 'is one of' },
  { value: 'STARTS_WITH', label: 'starts with' },
  { value: 'ENDS_WITH', label: 'ends with' },
  { value: 'CONTAINS', label: 'contains' },
  { value: 'MATCHES', label: 'matches regex' },
  { value: 'LESS_THAN', label: '<', hint: 'number' },
  { value: 'LESS_THAN_OR_EQUAL', label: '≤', hint: 'number' },
  { value: 'GREATER_THAN', label: '>', hint: 'number' },
  { value: 'GREATER_THAN_OR_EQUAL', label: '≥', hint: 'number' },
  { value: 'SEMVER_EQUAL', label: 'semver =', hint: '1.2.3' },
  { value: 'SEMVER_LESS_THAN', label: 'semver <', hint: '1.2.3' },
  { value: 'SEMVER_GREATER_THAN', label: 'semver >', hint: '1.2.3' },
  { value: 'BEFORE', label: 'before', hint: 'ISO date' },
  { value: 'AFTER', label: 'after', hint: 'ISO date' },
  { value: 'SEGMENT_MATCH', label: 'in segment' },
];

export const operatorLabel = (op: Operator) => OPERATORS.find((o) => o.value === op)?.label ?? op;

export function newClause(): Clause {
  return { attribute: 'key', operator: 'IN', values: [], negate: false };
}

/** An even split across variations, with any rounding remainder going to the first. */
export function evenRollout(variationCount: number): Rollout {
  const base = Math.floor(TOTAL_WEIGHT / variationCount);
  return {
    bucketBy: 'key',
    variations: Array.from({ length: variationCount }, (_, i) => ({
      variation: i,
      weight: i === 0 ? TOTAL_WEIGHT - base * (variationCount - 1) : base,
    })),
  };
}

export function rolloutTotal(rollout: Rollout) {
  return rollout.variations.reduce((sum, v) => sum + v.weight, 0);
}

export function variationLabel(variations: Variation[], index: number | null | undefined): string {
  if (index == null) return '—';
  const v = variations[index];
  if (!v) return `#${index}`;
  return v.name?.trim() ? v.name : formatValue(v.value);
}

export function formatValue(value: JsonValue | undefined): string {
  if (value === undefined) return '—';
  return typeof value === 'string' ? value : JSON.stringify(value);
}

export function describeServe(serve: VariationOrRollout | null | undefined, variations: Variation[]): string {
  if (!serve) return '—';
  if (serve.variation != null) return variationLabel(variations, serve.variation);
  if (serve.rollout) {
    return serve.rollout.variations
      .filter((v) => v.weight > 0)
      .map((v) => `${variationLabel(variations, v.variation)} ${formatPercent(v.weight)}`)
      .join(' / ');
  }
  return '—';
}

/** Client-side checks mirroring the server, for instant feedback before saving. */
export function validateTargeting(t: Targeting, variationCount: number): string[] {
  const errors: string[] = [];
  const checkServe = (serve: VariationOrRollout, where: string) => {
    if (serve.rollout) {
      const total = rolloutTotal(serve.rollout);
      if (total !== TOTAL_WEIGHT) errors.push(`${where}: rollout adds up to ${formatPercent(total)}, must be 100%`);
    } else if (serve.variation == null || serve.variation >= variationCount) {
      errors.push(`${where}: choose a variation`);
    }
  };
  t.rules.forEach((rule, i) => {
    if (rule.clauses.length === 0) errors.push(`Rule ${i + 1}: add at least one condition`);
    rule.clauses.forEach((c, j) => {
      if (c.values.length === 0) errors.push(`Rule ${i + 1}, condition ${j + 1}: add at least one value`);
      if (c.operator !== 'SEGMENT_MATCH' && !c.attribute?.trim()) errors.push(`Rule ${i + 1}, condition ${j + 1}: attribute is required`);
    });
    checkServe(rule.serve, `Rule ${i + 1}`);
  });
  checkServe(t.fallthrough, 'Default rule');
  const seen = new Set<string>();
  t.targets.forEach((target) => target.values.forEach((key) => {
    if (seen.has(key)) errors.push(`"${key}" is individually targeted more than once`);
    seen.add(key);
  }));
  return errors;
}

/** Parses a variation value typed into the UI according to the flag type. */
export function parseVariationValue(type: FlagType, raw: string): { value?: JsonValue; error?: string } {
  switch (type) {
    case 'BOOLEAN':
      if (raw === 'true' || raw === 'false') return { value: raw === 'true' };
      return { error: 'must be true or false' };
    case 'STRING':
      return { value: raw };
    case 'NUMBER': {
      const n = Number(raw);
      return raw.trim() !== '' && Number.isFinite(n) ? { value: n } : { error: 'must be a number' };
    }
    case 'JSON':
      try {
        const v = JSON.parse(raw);
        return v !== null && typeof v === 'object' ? { value: v } : { error: 'must be a JSON object or array' };
      } catch {
        return { error: 'invalid JSON' };
      }
  }
}

export function stringifyVariationValue(value: JsonValue): string {
  return typeof value === 'string' ? value : JSON.stringify(value, null, typeof value === 'object' ? 2 : 0);
}
