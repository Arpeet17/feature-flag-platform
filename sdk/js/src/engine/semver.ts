/** SemVer 2.0 subset matching the server: lenient about missing minor/patch, ignores build metadata. */
const PATTERN = /^v?(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?(?:\.(0|[1-9]\d*))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/;

interface SemVer {
  major: number;
  minor: number;
  patch: number;
  pre: string | null;
}

export function parseSemver(input: string): SemVer | null {
  const m = PATTERN.exec(input.trim());
  if (!m) return null;
  const nums = [m[1], m[2] ?? '0', m[3] ?? '0'].map(Number);
  if (nums.some((n) => !Number.isSafeInteger(n) || n > 2_147_483_647)) return null; // Java int range
  return { major: nums[0], minor: nums[1], patch: nums[2], pre: m[4] ?? null };
}

export function compareSemver(a: SemVer, b: SemVer): number {
  const c = Math.sign(a.major - b.major) || Math.sign(a.minor - b.minor) || Math.sign(a.patch - b.patch);
  if (c !== 0) return c;
  if (a.pre === null || b.pre === null) return a.pre === b.pre ? 0 : a.pre === null ? 1 : -1;
  const pa = a.pre.split('.');
  const pb = b.pre.split('.');
  for (let i = 0; i < Math.min(pa.length, pb.length); i++) {
    const na = /^\d+$/.test(pa[i]);
    const nb = /^\d+$/.test(pb[i]);
    let r: number;
    if (na && nb) r = Math.sign(Number(BigInt(pa[i]) - BigInt(pb[i])));
    else if (na) r = -1;
    else if (nb) r = 1;
    else r = pa[i] < pb[i] ? -1 : pa[i] > pb[i] ? 1 : 0;
    if (r !== 0) return r;
  }
  return Math.sign(pa.length - pb.length);
}
