import { describe, expect, it } from 'vitest';
import { buildIntakePath, intakeFromSearch, isValidNavigatePath } from '../../src/lib/api/shell/nav';

describe('intake params', () => {
	const cases: [string, string | null][] = [
		['食べる', '昨日、寿司を食べた。'],
		['A&B', 'x=1&y=2 #tag'],
		['100%', "it's (a) test*!"],
		['a/b//c', '日本語?&#=+ /']
	];
	for (const [q, sentence] of cases) {
		it(`round-trips ${q}`, () => {
			const path = buildIntakePath(q, sentence);
			expect(isValidNavigatePath(path)).toBe(true);
			const got = intakeFromSearch(path.slice(path.indexOf('?')));
			expect(got).toEqual({ q, sentence });
		});
	}

	it('omits an absent sentence', () => {
		const path = buildIntakePath('猫');
		expect(path).not.toContain('sentence');
		expect(intakeFromSearch(path.slice(path.indexOf('?')))).toEqual({ q: '猫', sentence: null });
	});

	it('the path rule rejects bad paths', () => {
		expect(isValidNavigatePath('//evil')).toBe(false);
		expect(isValidNavigatePath('/a//b')).toBe(false);
		expect(isValidNavigatePath('https://x')).toBe(false);
		expect(isValidNavigatePath('/a#b')).toBe(false);
	});
});
