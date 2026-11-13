import type { Job, MediaImportRequest } from '../api/generated';

/** The slice of the generated MediaApi the import flow needs (keeps this testable). */
export interface ImportMediaApi {
	importMedia(params: { mediaImportRequest: MediaImportRequest }): Promise<Job>;
}

/** Client-generated UUIDv7, minted once per user action (OpenAPI importMedia `import_id`). */
export function newImportId(now: number = Date.now(), rnd?: Uint8Array): string {
	const b = rnd ?? crypto.getRandomValues(new Uint8Array(16));
	const ts = BigInt(now);
	for (let i = 0; i < 6; i++) b[i] = Number((ts >> BigInt(8 * (5 - i))) & 0xffn);
	b[6] = ((b[6] ?? 0) & 0x0f) | 0x70;
	b[8] = ((b[8] ?? 0) & 0x3f) | 0x80;
	const h = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
	return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
}

/** Network-level failure (no HTTP response), as opposed to a server problem answer. */
function isNetworkError(e: unknown): boolean {
	return e instanceof TypeError || (e instanceof Error && e.name === 'FetchError');
}

/**
 * Start a media import. `request.importId` MUST already be set by the caller (minted once per
 * user action); on a network error the same request, with the same id, is sent again.
 */
export async function submitImport(
	api: ImportMediaApi,
	request: MediaImportRequest & { importId: string },
	attempts = 3
): Promise<Job> {
	let last: unknown;
	for (let i = 0; i < attempts; i++) {
		try {
			return await api.importMedia({ mediaImportRequest: request });
		} catch (e) {
			if (!isNetworkError(e)) throw e;
			last = e;
		}
	}
	throw last;
}
