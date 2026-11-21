// LF-W4: per-action "needs connection" for proxy operations, and uploads hidden in the shell (SHELL.md 9.4).
import { describe, expect, it } from 'vitest';
import ops from '../../../contracts/api/shell/ops.json';
import { actionNeedsConnection, uploadsOffered } from '../../src/lib/ui/access';

const CLASSES = ops as Record<string, string>;
const ids = (cls: string): string[] => Object.entries(CLASSES).filter(([, c]) => c === cls).map(([id]) => id);

describe('actionNeedsConnection', () => {
	it('disables a proxy action in shell mode while offline; enabled online or outside the shell', () => {
		expect(ids('proxy').length).toBeGreaterThan(0);
		for (const id of ids('proxy')) {
			expect(actionNeedsConnection(id, { present: true, online: false })).toBe(true);
			expect(actionNeedsConnection(id, { present: true, online: true })).toBe(false);
			expect(actionNeedsConnection(id, { present: false, online: false })).toBe(false);
		}
	});

	it('never disables a local operation, nor an unknown id', () => {
		for (const id of ids('local')) expect(actionNeedsConnection(id, { present: true, online: false })).toBe(false);
		expect(actionNeedsConnection('noSuchOp', { present: true, online: false })).toBe(false);
	});
});

describe('uploads', () => {
	it('upload entry points are hidden in shell mode and offered on the web', () => {
		expect(uploadsOffered({ present: true })).toBe(false);
		expect(uploadsOffered({ present: false })).toBe(true);
	});
});
