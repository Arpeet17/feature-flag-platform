import type { EvaluationContext, EvaluationReason } from '../types.js';
import { attribute, matchesClause } from './clauses.js';
import type { EvaluationResult, FlagDefinition, Rollout, Ruleset, VariationOrRollout } from './model.js';
import { bucket } from './murmur3.js';

/**
 * Local flag evaluation – a faithful port of the server's FlagEvaluator. Order: off →
 * prerequisites → individual targets → rules (first match) → default rule. Pure and
 * synchronous: no I/O, so it runs in microseconds on every call.
 */
export class Evaluator {
  constructor(private readonly ruleset: Ruleset) {}

  evaluate(flagKey: string, context: EvaluationContext): EvaluationResult {
    const flag = this.ruleset.flags[flagKey];
    if (!flag) return error('FLAG_NOT_FOUND');
    return this.evaluateFlag(flag, context, new Set());
  }

  private evaluateFlag(flag: FlagDefinition, context: EvaluationContext, visiting: Set<string>): EvaluationResult {
    if (visiting.has(flag.key)) return error('MALFORMED_FLAG'); // prerequisite cycle
    visiting.add(flag.key);
    try {
      const t = flag.targeting;
      if (!flag.enabled) return serve(flag, t.offVariation, { kind: 'OFF' });

      for (const p of t.prerequisites) {
        if (!this.prerequisiteSatisfied(p.flagKey, p.variation, context, visiting)) {
          return serve(flag, t.offVariation, { kind: 'PREREQUISITE_FAILED', prerequisiteKey: p.flagKey });
        }
      }
      for (const target of t.targets) {
        if (target.values.includes(context.key)) return serve(flag, target.variation, { kind: 'TARGET_MATCH' });
      }
      for (let i = 0; i < t.rules.length; i++) {
        const rule = t.rules[i];
        if (rule.clauses.length > 0 && rule.clauses.every((c) => matchesClause(c, context, (s) => this.inSegment(s, context)))) {
          return serveVariationOrRollout(flag, rule.serve, context, (inRollout) => ({
            kind: 'RULE_MATCH', ruleIndex: i, ruleId: rule.id ?? undefined, ...(inRollout ? { inRollout } : {}),
          }));
        }
      }
      return serveVariationOrRollout(flag, t.fallthrough, context,
        (inRollout) => ({ kind: 'FALLTHROUGH', ...(inRollout ? { inRollout } : {}) }));
    } finally {
      visiting.delete(flag.key);
    }
  }

  private prerequisiteSatisfied(key: string, variation: number, context: EvaluationContext, visiting: Set<string>): boolean {
    const prereq = this.ruleset.flags[key];
    if (!prereq || !prereq.enabled) return false;
    const result = this.evaluateFlag(prereq, context, visiting);
    return result.reason.kind !== 'ERROR' && result.variation === variation;
  }

  private inSegment(key: string, context: EvaluationContext): boolean {
    const segment = this.ruleset.segments[key];
    if (!segment) return false;
    if (segment.excluded.includes(context.key)) return false;
    if (segment.included.includes(context.key)) return true;
    return segment.rules.some((r) => r.clauses.length > 0 && r.clauses.every((c) => matchesClause(c, context, null)));
  }
}

function serveVariationOrRollout(flag: FlagDefinition, serve: VariationOrRollout | null, context: EvaluationContext,
                                 reason: (inRollout: boolean) => EvaluationReason): EvaluationResult {
  if (!serve) return error('MALFORMED_FLAG');
  if (serve.variation != null) return serveIndex(flag, serve.variation, reason(false));
  if (!serve.rollout || serve.rollout.variations.length === 0) return error('MALFORMED_FLAG');
  return serveIndex(flag, pickVariation(flag, serve.rollout, context), reason(true));
}

function pickVariation(flag: FlagDefinition, rollout: Rollout, context: EvaluationContext): number {
  const value = attribute(context, rollout.bucketBy || 'key');
  // Contexts without the bucketing attribute share bucket 0, exactly like the server.
  const b = value === undefined ? 0 : bucket(flag.key, flag.salt, String(value));
  let cumulative = 0;
  for (const wv of rollout.variations) {
    cumulative += wv.weight;
    if (b < cumulative) return wv.variation;
  }
  return rollout.variations[rollout.variations.length - 1].variation;
}

function serve(flag: FlagDefinition, index: number, reason: EvaluationReason): EvaluationResult {
  return serveIndex(flag, index, reason);
}

function serveIndex(flag: FlagDefinition, index: number, reason: EvaluationReason): EvaluationResult {
  const v = flag.variations[index];
  if (v === undefined) return error('MALFORMED_FLAG');
  return { value: v.value, variation: index, reason };
}

function error(errorKind: NonNullable<EvaluationReason['errorKind']>): EvaluationResult {
  return { value: null, variation: null, reason: { kind: 'ERROR', errorKind } };
}
