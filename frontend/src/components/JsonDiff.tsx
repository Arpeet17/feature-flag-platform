import type { JsonValue } from '../api/types';

type Line = { kind: 'same' | 'added' | 'removed'; text: string };

/**
 * Line-level diff of two pretty-printed JSON documents using an LCS table. Audit payloads are
 * small (a flag's targeting), so the O(n·m) algorithm is perfectly adequate.
 */
export function diffLines(before: string, after: string): Line[] {
  const a = before ? before.split('\n') : [];
  const b = after ? after.split('\n') : [];
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: Line[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ kind: 'removed', text: a[i++] });
    } else {
      out.push({ kind: 'added', text: b[j++] });
    }
  }
  while (i < a.length) out.push({ kind: 'removed', text: a[i++] });
  while (j < b.length) out.push({ kind: 'added', text: b[j++] });
  return out;
}

const pretty = (v: JsonValue | null) => (v == null ? '' : JSON.stringify(v, null, 2));

export function JsonDiff({ before, after }: { before: JsonValue | null; after: JsonValue | null }) {
  const lines = diffLines(pretty(before), pretty(after));
  if (lines.length === 0) return <p className="muted">No details recorded.</p>;
  return (
    <pre className="diff" aria-label="Change diff">
      {lines.map((line, i) => (
        <div key={i} className={`diff-${line.kind}`}>
          <span className="diff-sign" aria-hidden>{line.kind === 'added' ? '+' : line.kind === 'removed' ? '−' : ' '}</span>
          {line.text}
        </div>
      ))}
    </pre>
  );
}
