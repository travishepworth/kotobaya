import { describe, expect, it } from 'vitest';
import { newImportId, submitImport } from '../src/lib/media/import';
import type { Job, MediaImportRequest } from '../src/lib/api/generated';

describe('submitImport', () => {
	it('reuses the same import_id when the first call fails with a network error', async () => {
		const seen: (string | undefined)[] = [];
		let calls = 0;
		const api = {
			importMedia: async (p: { mediaImportRequest: MediaImportRequest }): Promise<Job> => {
				seen.push(p.mediaImportRequest.importId);
				if (++calls === 1) throw new TypeError('Failed to fetch');
				return { id: 'job1' } as Job;
			}
		};
		const importId = newImportId();
		const job = await submitImport(api, { source: 'paste', text: 'x', importId });
		expect(job.id).toBe('job1');
		expect(seen).toEqual([importId, importId]);
	});

	it('does not retry server problem answers', async () => {
		let calls = 0;
		const api = {
			importMedia: async (): Promise<Job> => {
				calls++;
				throw new Error('422');
			}
		};
		await expect(submitImport(api, { source: 'paste', importId: 'a' })).rejects.toThrow('422');
		expect(calls).toBe(1);
	});

	it('mints UUIDv7-shaped ids', () => {
		expect(newImportId()).toMatch(
			/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
		);
	});
});
