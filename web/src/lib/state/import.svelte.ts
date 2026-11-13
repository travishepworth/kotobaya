// Import screens (/import jpdb history, /import/mapping unresolved jpdb vocabulary).
import {
	DictionaryApi,
	JpdbVocabResolutionPutResolutionEnum,
	ListJpdbVocabStatusEnum,
	MediaApi,
	type DictEntrySummary,
	type Job,
	type JpdbVocab,
	type JpdbVocabResolutionPut,
	type JpdbVocabResolutionResult
} from '../api/generated';
import { apiConfig } from '../api/client';
import { listOf, splitCounters, type UnmappedKanji, type UnmappedVocab } from '../../routes/import/counters';
import { tm, type MapMessageKey } from '../../routes/import/mapping/messages';
import { isRunning, JobWatch, putUploadBlob } from './jobs.svelte';

export type { DictEntrySummary, Job, JpdbVocab, JpdbVocabResolutionResult } from '../api/generated';
export { isRunning } from './jobs.svelte';

const mediaApi = new MediaApi(apiConfig);
const dictApi = new DictionaryApi(apiConfig);

/* ---------- /import ---------- */

/** Call `dispose()` on destroy (closes the job subscription). */
export class ImportPage {
	file = $state<File | null>(null);
	importReviews = $state(true);
	importWordStatus = $state(true);
	uploading = $state(false);
	readonly watch = new JobWatch();

	get job(): Job | null {
		return this.watch.job;
	}

	// Job.result is passed through raw (snake_case), so unknown extra counters survive.
	readonly result = $derived<Record<string, unknown> | null>(
		this.watch.job && this.watch.job.status === 'succeeded' ? (this.watch.job.result ?? null) : null
	);
	readonly counters = $derived(this.result ? splitCounters(this.result) : null);
	readonly unmapped = $derived(this.result ? listOf<UnmappedVocab>(this.result, 'unmapped') : []);
	readonly kanjiUnmapped = $derived(this.result ? listOf<UnmappedKanji>(this.result, 'kanji_unmapped') : []);

	dispose(): void {
		this.watch.stop();
	}

	async start(): Promise<void> {
		const file = this.file;
		if (!file || this.uploading) return;
		this.uploading = true;
		try {
			const hash = await putUploadBlob(await file.arrayBuffer(), 'application/json');
			const started = await mediaApi.importJpdbHistory({
				jpdbHistoryImportRequest: {
					blobHash: hash,
					importReviews: this.importReviews,
					importWordStatus: this.importWordStatus
				}
			});
			this.watch.show(started);
		} catch {
			// The API client already showed an error toast.
		} finally {
			this.uploading = false;
		}
	}

	reset(): void {
		this.watch.clear();
		this.file = null;
	}

	isRunning(): boolean {
		return isRunning(this.watch.job);
	}
}

/* ---------- /import/mapping ---------- */

const PAGE_SIZE = 50;
const KNOWN_STATUSES: readonly string[] = ['queued', 'running', 'succeeded', 'failed', 'cancelled'];

export interface Republish {
	job: Job;
	title: string;
}

function titleFor(res: JpdbVocabResolutionResult, job: Job): string {
	const mediaId = job.payload['media_id'];
	return res.vocab.media.find((m) => m.mediaId === mediaId)?.title ?? String(mediaId ?? '');
}

export function jobStatusLabel(s: string): string {
	return KNOWN_STATUSES.includes(s) ? tm(`map.status.${s}` as MapMessageKey) : tm('map.status.other');
}

export class MappingPage {
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);
	private retryMore = false;
	items = $state<JpdbVocab[]>([]);
	cursor = $state<string | null>(null);
	loading = $state(false);
	loaded = $state(false);
	// Re-publish jobs started by corrections made on this page; statuses are as returned by the API.
	republish = $state<Republish[]>([]);

	/** Load the failed page again. */
	retry(): void {
		void this.load(this.retryMore);
	}

	async load(more: boolean): Promise<void> {
		if (this.loading) return;
		this.loading = true;
		try {
			const page = await mediaApi.listJpdbVocab({
				status: ListJpdbVocabStatusEnum.Unresolved,
				limit: PAGE_SIZE,
				cursor: more ? (this.cursor ?? undefined) : undefined
			});
			// Keyset pages can repeat a row when totals change; keep the first occurrence.
			const seen = new Set(this.items.map((v) => v.jpdbVid));
			const fresh = page.items.filter((v) => !seen.has(v.jpdbVid));
			this.items = more ? [...this.items, ...fresh] : fresh;
			this.cursor = page.nextCursor;
			this.loadFailed = false;
		} catch {
			this.loadFailed = true; // the client already toasted
			this.retryMore = more;
		} finally {
			this.loading = false;
			this.loaded = true;
		}
	}

	// A resolved row leaves the review queue; its re-publish jobs are listed below the table.
	resolved(res: JpdbVocabResolutionResult): void {
		this.items = this.items.filter((v) => v.jpdbVid !== res.vocab.jpdbVid);
		const incoming = res.jobs.map((job) => ({ job, title: titleFor(res, job) }));
		const incomingIds = new Set(incoming.map((r) => r.job.id));
		this.republish = [...this.republish.filter((r) => !incomingIds.has(r.job.id)), ...incoming];
	}
}

/** One unresolved jpdb vocabulary row: dictionary search and map/ignore. */
export class MappingRowState {
	query = $state('');
	candidates = $state<DictEntrySummary[]>([]);
	searching = $state(false);
	busy = $state(false);
	readonly #vocab: () => JpdbVocab;
	readonly #onResolved: () => (res: JpdbVocabResolutionResult) => void;

	constructor(vocab: () => JpdbVocab, onResolved: () => (res: JpdbVocabResolutionResult) => void) {
		this.#vocab = vocab;
		this.#onResolved = onResolved;
	}

	/** Pre-fill with the spelling (or the reading when there is none) and show candidates. */
	start(): void {
		const v = this.#vocab();
		this.query = v.spelling !== '' ? v.spelling : v.reading;
		void this.search();
	}

	async search(): Promise<void> {
		const q = this.query.trim();
		if (q === '') {
			this.candidates = [];
			return;
		}
		this.searching = true;
		try {
			this.candidates = (await dictApi.searchDictionary({ q })).items;
		} catch {
			// The API client already showed an error toast.
		} finally {
			this.searching = false;
		}
	}

	private async put(body: JpdbVocabResolutionPut): Promise<void> {
		if (this.busy) return;
		this.busy = true;
		try {
			const res = await mediaApi.putJpdbVocabResolution({
				jpdbVid: this.#vocab().jpdbVid,
				jpdbVocabResolutionPut: body
			});
			this.#onResolved()(res);
		} catch {
			// The API client already showed an error toast.
		} finally {
			this.busy = false;
		}
	}

	map(seq: number): Promise<void> {
		return this.put({ resolution: JpdbVocabResolutionPutResolutionEnum.Mapped, entrySeq: seq });
	}

	ignore(): Promise<void> {
		return this.put({ resolution: JpdbVocabResolutionPutResolutionEnum.Ignored });
	}
}
