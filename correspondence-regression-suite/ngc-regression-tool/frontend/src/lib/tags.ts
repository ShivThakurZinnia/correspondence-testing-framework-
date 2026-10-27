/** "mapper:anniversary,mapper:new-business,client:GILI" <-> ["mapper:anniversary", ...] */
export const parseTagExpression = (text: string | null | undefined): string[] =>
  (text ?? '').split(',').map((t) => t.trim()).filter(Boolean);

export const tagExpression = (refs: string[]): string | null => (refs.length ? refs.join(',') : null);

export const tagRef = (category: string, code: string): string => `${category.toLowerCase()}:${code}`;

export function toggleTag(refs: string[], ref: string): string[] {
  return refs.includes(ref) ? refs.filter((r) => r !== ref) : [...refs, ref];
}

/** Same kind = any of them, different kinds = all of them. Explains the selection in words. */
export function describeSelection(refs: string[]): string {
  if (refs.length === 0) return 'every enabled test case';
  const byKind = new Map<string, string[]>();
  for (const r of refs) {
    const [kind, code] = r.split(':');
    byKind.set(kind, [...(byKind.get(kind) ?? []), code]);
  }
  return [...byKind.entries()].map(([kind, codes]) => `${kind} ${codes.length > 1 ? 'is any of' : 'is'} ${codes.join(', ')}`).join(' and ');
}
