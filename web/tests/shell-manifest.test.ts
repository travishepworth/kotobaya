import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { buildManifest, MANIFEST_NAME } from '../scripts/shell-manifest.ts';

describe('shell manifest', () => {
	it('lists every emitted file with its sha256 and skips itself', () => {
		const dir = mkdtempSync(join(tmpdir(), 'shell-manifest-'));
		mkdirSync(join(dir, '_app', 'immutable'), { recursive: true });
		writeFileSync(join(dir, 'index.html'), '<html></html>');
		writeFileSync(join(dir, '_app', 'immutable', 'a.js'), 'let a = 1;');
		writeFileSync(join(dir, MANIFEST_NAME), 'stale');
		const m = buildManifest(dir, '1.2.3', 'abc1234');
		const sha = (s: string) => createHash('sha256').update(s).digest('hex');
		expect(m.web_version).toBe('1.2.3');
		expect(m.build_sha).toBe('abc1234');
		expect(m.min_local_api).toBe(1);
		expect(m.files).toEqual({
			'index.html': sha('<html></html>'),
			'_app/immutable/a.js': sha('let a = 1;')
		});
	});
});
