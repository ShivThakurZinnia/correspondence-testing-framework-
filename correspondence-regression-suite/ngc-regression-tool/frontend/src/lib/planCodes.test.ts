import { describe, expect, it } from 'vitest';
import { invalidPlanCodes, parsePlanCodes } from './planCodes';

describe('parsePlanCodes', () => {
  it('splits on commas, semicolons and spaces and keeps the order', () => {
    expect(parsePlanCodes('GCFIA901, GCFIA902;GCFIA903  TL0101')).toEqual(['GCFIA901', 'GCFIA902', 'GCFIA903', 'TL0101']);
  });
  it('drops blanks and repeats regardless of case', () => {
    expect(parsePlanCodes(' ,GCFIA901,, gcfia901 ,GCFIA902,')).toEqual(['GCFIA901', 'GCFIA902']);
    expect(parsePlanCodes('')).toEqual([]);
  });
  it('flags codes the server would reject', () => {
    expect(invalidPlanCodes(['GCFIA901', 'bad!code', 'X'.repeat(31), 'TL.01-a_b'])).toEqual(['bad!code', 'X'.repeat(31)]);
  });
});
