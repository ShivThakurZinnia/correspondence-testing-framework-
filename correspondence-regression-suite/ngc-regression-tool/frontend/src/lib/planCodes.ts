/** "GCFIA901, GCFIA902 gcfia901;GCFIA903" -> ["GCFIA901", "GCFIA902", "GCFIA903"]: commas, semicolons and spaces all separate; repeats are dropped. */
export function parsePlanCodes(text: string): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const raw of text.split(/[\s,;]+/)) {
    const code = raw.trim();
    if (code && !seen.has(code.toUpperCase())) {
      seen.add(code.toUpperCase());
      out.push(code);
    }
  }
  return out;
}

export const PLAN_CODE_PATTERN = /^[A-Za-z0-9_.-]{1,30}$/;

export const invalidPlanCodes = (codes: string[]): string[] => codes.filter((c) => !PLAN_CODE_PATTERN.test(c));
