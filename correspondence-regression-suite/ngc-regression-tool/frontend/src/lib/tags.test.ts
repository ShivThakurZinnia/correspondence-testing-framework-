import { describe, expect, it } from 'vitest';
import { describeSelection, parseTagExpression, tagExpression, tagRef, toggleTag } from './tags';

describe('tag helpers', () => {
  it('round-trips the expression the server stores', () => {
    expect(parseTagExpression(' mapper:anniversary , client:GILI,, ')).toEqual(['mapper:anniversary', 'client:GILI']);
    expect(parseTagExpression(null)).toEqual([]);
    expect(tagExpression([])).toBeNull();
    expect(tagExpression(['a:b', 'c:d'])).toBe('a:b,c:d');
    expect(tagRef('MAPPER', 'new-business')).toBe('mapper:new-business');
  });
  it('toggles', () => {
    expect(toggleTag(['a:b'], 'c:d')).toEqual(['a:b', 'c:d']);
    expect(toggleTag(['a:b', 'c:d'], 'a:b')).toEqual(['c:d']);
  });
  it('explains that a kind means any-of and kinds combine with and', () => {
    expect(describeSelection([])).toBe('every enabled test case');
    expect(describeSelection(['mapper:anniversary'])).toBe('mapper is anniversary');
    expect(describeSelection(['mapper:anniversary', 'mapper:policy-page', 'client:GILI'])).toBe('mapper is any of anniversary, policy-page and client is GILI');
  });
});
