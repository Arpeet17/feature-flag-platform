import type { Clause, Operator } from '../../api/types';
import { ValuesInput } from '../../components/ui';
import { OPERATORS } from '../../lib/targeting';

const COMMON_ATTRIBUTES = ['key', 'email', 'country', 'plan', 'appVersion', 'platform', 'orgId', 'createdAt'];

export function ClauseEditor({ clause, onChange, onRemove, segmentKeys, disabled, allowSegments = true, index }: {
  clause: Clause;
  onChange: (c: Clause) => void;
  onRemove: () => void;
  segmentKeys: string[];
  disabled?: boolean;
  allowSegments?: boolean;
  index: number;
}) {
  const isSegment = clause.operator === 'SEGMENT_MATCH';
  const operators = allowSegments ? OPERATORS : OPERATORS.filter((o) => o.value !== 'SEGMENT_MATCH');
  const hint = OPERATORS.find((o) => o.value === clause.operator)?.hint;

  return (
    <fieldset className="clause" disabled={disabled}>
      <legend className="sr-only">Condition {index + 1}</legend>
      <span className="clause-if">{index === 0 ? 'If' : 'and'}</span>
      {isSegment ? (
        <span className="clause-attr muted">context</span>
      ) : (
        <>
          <input className="clause-attr" aria-label="Attribute" list="common-attributes" value={clause.attribute ?? ''}
                 placeholder="attribute" onChange={(e) => onChange({ ...clause, attribute: e.target.value })} />
          <datalist id="common-attributes">{COMMON_ATTRIBUTES.map((a) => <option key={a} value={a} />)}</datalist>
        </>
      )}
      <select aria-label="Negate" value={clause.negate ? 'not' : 'is'} onChange={(e) => onChange({ ...clause, negate: e.target.value === 'not' })}>
        <option value="is">{isSegment ? 'is' : 'does'}</option>
        <option value="not">{isSegment ? 'is not' : 'does not'}</option>
      </select>
      <select aria-label="Operator" value={clause.operator}
              onChange={(e) => {
                const operator = e.target.value as Operator;
                const switchingKind = (operator === 'SEGMENT_MATCH') !== isSegment;
                onChange({ ...clause, operator, values: switchingKind ? [] : clause.values,
                  attribute: operator === 'SEGMENT_MATCH' ? null : clause.attribute ?? 'key' });
              }}>
        {operators.map((o) => <option key={o.value} value={o.value}>{clause.negate && o.value === 'IN' ? 'one of' : o.label}</option>)}
      </select>
      <ValuesInput ariaLabel="Values" values={clause.values} onChange={(values) => onChange({ ...clause, values })}
                   placeholder={isSegment ? 'segment…' : hint ?? 'value…'} suggestions={isSegment ? segmentKeys : undefined} />
      <button type="button" className="icon-btn" aria-label="Remove condition" onClick={onRemove}>×</button>
    </fieldset>
  );
}
