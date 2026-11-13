// Media screens (/media, /media/new, /media/[id] coverage) and the coverage "unknown words" list.
import { untrack } from 'svelte';
import {
	DecksApi,
	MediaApi,
	MediaImportRequestFormatEnum,
	MediaImportRequestOptionsRubyEnum,
	MediaKind,
	WordsApi,
	type Coverage,
	type DeckItem,
	type Job,
	type Media,
	type MediaDeckOptions,
	type MediaImportFile
} from '../api/generated';
import { apiConfig, ResponseError } from '../api/client';
import { HIDE_CLASSES, loadFilters, saveFilters, type CoverageFilters, type HideClass } from '../coverage/filters';
import { tc } from '../coverage/messages';
import { newImportId, submitImport } from '../media/import';
import { tm } from '../media/messages';
import { pushToast } from '../toasts.svelte';
import { isRunning, JobWatch, putUploadBlob } from './jobs.svelte';

export { MediaImportRequestFormatEnum, MediaImportRequestOptionsRubyEnum, MediaKind } from '../api/generated';
export type { Coverage, CoverageCurveInner, DeckItem, Job, Media } from '../api/generated';
export { HIDE_CLASSES } from '../coverage/filters';
export type { HideClass } from '../coverage/filters';
export { isRunning } from './jobs.svelte';

const mediaApi = new MediaApi(apiConfig);
const decksApi = new DecksApi(apiConfig);
const wordsApi = new WordsApi(apiConfig);

export const MEDIA_KINDS = Object.values(MediaKind);
export const IMPORT_FORMATS = Object.values(MediaImportRequestFormatEnum);

/* ---------- /media ---------- */

export class MediaListPage {
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);
	private retryMore = false;
	items = $state<Media[]>([]);
	cursor = $state<string | null>(null);
	loading = $state(false);
	kind = $state<MediaKind | ''>('');
	q = $state('');
	editing = $state<string | null>(null);
	draftTitle = $state('');

	/** Load the failed page again. */
	retry(): void {
		void this.load(this.retryMore);
	}

	async load(more: boolean): Promise<void> {
		if (this.loading) return;
		this.loading = true;
		try {
			const page = await mediaApi.listMedia({
				kind: this.kind || undefined,
				q: this.q.trim() || undefined,
				limit: 50,
				cursor: more ? (this.cursor ?? undefined) : undefined
			});
			this.items = more ? [...this.items, ...page.items] : page.items;
			this.cursor = page.nextCursor;
			this.loadFailed = false;
		} catch {
			this.loadFailed = true; // the client already toasted
			this.retryMore = more;
		} finally {
			this.loading = false;
		}
	}

	startRename(m: Media): void {
		this.editing = m.id;
		this.draftTitle = m.title;
	}

	cancelRename(): void {
		this.editing = null;
	}

	async saveRename(m: Media): Promise<void> {
		const title = this.draftTitle.trim();
		if (!title) return;
		try {
			const updated = await mediaApi.updateMedia({ mediaId: m.id, mediaPatch: { title } });
			this.items = this.items.map((x) => (x.id === m.id ? { ...x, ...updated } : x));
			this.editing = null;
		} catch {
			// toast shown by the client
		}
	}

	async reparse(m: Media): Promise<void> {
		try {
			await mediaApi.reparseMedia({ mediaId: m.id });
			pushToast(tm('med.reparseQueued'), 'info');
		} catch {
			// toast shown by the client
		}
	}

	/** Delete (the view asks for confirmation first). */
	async remove(m: Media): Promise<void> {
		try {
			await mediaApi.deleteMedia({ mediaId: m.id });
			this.items = this.items.filter((x) => x.id !== m.id);
			pushToast(tm('med.deleted'), 'info');
		} catch {
			// toast shown by the client
		}
	}
}

/* ---------- /media/[id] (coverage) ---------- */

/** Construct during component init; call `mount()` once mounted (filters come from localStorage). */
export class MediaCoveragePage {
	id = $state('');
	media = $state<Media | null>(null);
	coverage = $state<Coverage | null>(null);
	missing = $state(false);
	/** Filters as stored/chosen. A stored segment of another media is masked by `filters` below. */
	private stored = $state<CoverageFilters>(loadFilters());
	ready = $state(false);
	private gen = 0;

	readonly segments = $derived(this.media?.segments ?? []);
	/**
	 * The filters in effect. A stored segment from another media is ignored by the server with 404;
	 * once the media is known such a segment is dropped (derived, so no effect writes what it reads).
	 */
	readonly filters = $derived.by((): CoverageFilters => {
		const f = this.stored;
		if (this.media && f.segmentId && !this.segments.some((s) => s.id === f.segmentId)) {
			return { ...f, segmentId: null };
		}
		return f;
	});

	constructor(id: () => string) {
		$effect(() => {
			const mediaId = id();
			untrack(() => this.loadMedia(mediaId));
		});
		$effect(() => {
			if (!this.ready) return;
			const segmentId = this.filters.segmentId;
			const hide = this.filters.hide;
			const mediaId = id();
			untrack(() => void this.fetchCoverage(mediaId, segmentId, hide));
		});
	}

	mount(): void {
		this.stored = loadFilters();
		this.ready = true;
	}

	private loadMedia(mediaId: string): void {
		this.id = mediaId;
		void (async () => {
			try {
				this.media = await mediaApi.getMedia({ mediaId });
			} catch (e) {
				if (e instanceof ResponseError && (e.response.status === 404 || e.response.status === 410)) {
					this.missing = true;
				}
			}
		})();
	}

	private async fetchCoverage(mediaId: string, segmentId: string | null, hide: HideClass[]): Promise<void> {
		const mine = ++this.gen;
		try {
			const c = await mediaApi.getMediaCoverage({
				mediaId,
				segmentId: segmentId ?? undefined,
				hide: new Set(hide)
			});
			if (mine === this.gen) this.coverage = c;
		} catch {
			/* toast shown */
		}
	}

	/** Reload the coverage with the current filters (e.g. after words were marked). */
	reloadCoverage(): void {
		void this.fetchCoverage(this.id, this.filters.segmentId, this.filters.hide);
	}

	toggle(c: HideClass, on: boolean): void {
		const f = this.filters;
		const hide = on ? [...f.hide, c] : f.hide.filter((x) => x !== c);
		this.stored = { ...f, hide: HIDE_CLASSES.filter((x) => hide.includes(x)) };
		saveFilters(this.stored);
	}

	pickSegment(v: string): void {
		this.stored = { ...this.filters, segmentId: v === '' ? null : v };
		saveFilters(this.stored);
	}
}

/** Unknown words of a media deck, with quick status marking. Construct during component init. */
export class UnknownWordsState {
	sort = $state<'occurrence_count' | 'freq_rank'>('occurrence_count');
	items = $state<DeckItem[]>([]);
	cursor = $state<string | null>(null);
	loaded = $state(false);
	/** The last load failed (the client already toasted); the view shows the error state and offers `retry`. */
	loadFailed = $state(false);
	private retryMore = false;
	readonly #deckId: () => string;

	constructor(deckId: () => string) {
		this.#deckId = deckId;
		$effect(() => {
			void this.sort;
			void deckId();
			untrack(() => void this.load(false));
		});
	}

	async load(more: boolean): Promise<void> {
		try {
			const p = await decksApi.listDeckItems({
				deckId: this.#deckId(),
				status: new Set(['new'] as const),
				sort: this.sort,
				limit: 50,
				cursor: more ? (this.cursor ?? undefined) : undefined
			});
			this.items = more ? [...this.items, ...p.items] : p.items;
			this.cursor = p.nextCursor;
			this.loadFailed = false;
		} catch {
			this.loadFailed = true; // the client already toasted
			this.retryMore = more;
		} finally {
			this.loaded = true;
		}
	}

	retry(): void {
		void this.load(this.retryMore);
	}

	async mark(item: DeckItem, status: 'known' | 'ignored' | 'never_forget', onchanged: () => void): Promise<void> {
		if (item.entrySeq == null) return;
		try {
			await wordsApi.setWordStatus({ seq: item.entrySeq, wordStatusSet: { status } });
			this.items = this.items.filter((x) => x.itemKey !== item.itemKey);
			pushToast(tc('cov.marked'), 'info');
			onchanged();
		} catch {
			/* toast shown */
		}
	}
}

/* ---------- /media/new ---------- */

export type ImportTab = 'files' | 'url' | 'paste' | 'jpdb';
export const IMPORT_TABS: ImportTab[] = ['files', 'url', 'paste', 'jpdb'];

// Mirrors the worker's normalize_deck_id: full jpdb URL or `<kind>/<id>`; a bare number is rejected.
function normalizeDeckId(raw: string): string | null {
	const m = /^(?:https?:\/\/(?:www\.)?jpdb\.io)?\/?([a-z][a-z-]*)\/(\d+)(?:[/?#].*)?$/i.exec(raw.trim());
	if (!m || ['vocabulary', 'kanji', 'api', 'deck'].includes(m[1].toLowerCase())) return null;
	return `${m[1].toLowerCase()}/${m[2]}`;
}

/** Call `dispose()` on destroy (closes the job subscription). */
export class MediaNewPage {
	tab = $state<ImportTab>('files');
	title = $state('');
	kind = $state<MediaKind>(MediaKind.Other);
	files = $state<File[]>([]);
	audio = $state<Record<number, File | undefined>>({});
	format = $state<MediaImportRequestFormatEnum>(MediaImportRequestFormatEnum.Auto);
	ruby = $state<MediaImportRequestOptionsRubyEnum>(MediaImportRequestOptionsRubyEnum.Strip);
	cutAudio = $state(false);
	url = $state('');
	text = $state('');
	jpdbId = $state('');
	priority = $state(5);
	perDay = $state('');
	enabled = $state(true);

	busy = $state(false);
	upload = $state<{ done: number; total: number } | null>(null);
	failed = $state(false);
	readonly watch = new JobWatch();
	// Minted once per user action; kept across retries, dropped by reset().
	private importId: string | null = null;
	// Hashes already uploaded, so a retry does not re-read and re-send files.
	private uploaded: MediaImportFile[] | null = null;

	get job(): Job | null {
		return this.watch.job;
	}

	readonly deckId = $derived(normalizeDeckId(this.jpdbId));
	readonly result = $derived<Record<string, unknown> | null>(
		this.watch.job && this.watch.job.status === 'succeeded' ? (this.watch.job.result ?? null) : null
	);
	readonly mediaId = $derived.by(() => {
		const fromResult = this.result?.['media_id'];
		if (typeof fromResult === 'string') return fromResult;
		const fromPayload = (this.watch.job?.payload as Record<string, unknown> | undefined)?.['media_id'];
		return typeof fromPayload === 'string' ? fromPayload : null;
	});
	readonly unmappedCount = $derived.by(() => {
		const u = this.result?.['unmapped'];
		return Array.isArray(u) ? u.length : typeof u === 'number' ? u : 0;
	});
	readonly canStart = $derived(
		this.tab === 'jpdb'
			? this.deckId !== null
			: this.title.trim() !== '' &&
					(this.tab === 'files'
						? this.files.length > 0
						: this.tab === 'url'
							? this.url.trim() !== ''
							: this.text !== '')
	);

	dispose(): void {
		this.watch.stop();
	}

	setFiles(list: FileList | null): void {
		this.files = [...(list ?? [])];
		this.audio = {};
		this.uploaded = null;
	}

	setAudio(i: number, f: File | undefined): void {
		this.audio[i] = f;
		this.uploaded = null;
	}

	private async uploadAll(): Promise<MediaImportFile[]> {
		if (this.uploaded) return this.uploaded;
		const files = this.files;
		const audio = this.audio;
		const total = files.length + files.filter((_, i) => audio[i]).length;
		let done = 0;
		this.upload = { done, total };
		const out: MediaImportFile[] = [];
		for (const [i, f] of files.entries()) {
			const blobHash = await putUploadBlob(await f.arrayBuffer(), f.type || 'application/octet-stream');
			this.upload = { done: ++done, total };
			const a = audio[i];
			let audioBlobHash: string | undefined;
			if (a) {
				audioBlobHash = await putUploadBlob(await a.arrayBuffer(), a.type || 'application/octet-stream');
				this.upload = { done: ++done, total };
			}
			out.push({ filename: f.name, blobHash, ...(audioBlobHash ? { audioBlobHash } : {}) });
		}
		this.uploaded = out;
		return out;
	}

	private deckOptions(): MediaDeckOptions {
		const n = this.perDay.trim() === '' ? null : Number(this.perDay);
		return { priority: this.priority, enabled: this.enabled, newCardsPerDay: n };
	}

	async start(): Promise<void> {
		if (this.busy) return;
		this.busy = true;
		this.failed = false;
		try {
			let started: Job;
			if (this.tab === 'jpdb') {
				started = await mediaApi.importJpdbDeck({
					jpdbDeckImportRequest: {
						jpdbId: this.deckId ?? this.jpdbId.trim(),
						kind: this.kind,
						...(this.title.trim() ? { title: this.title.trim() } : {}),
						deck: this.deckOptions()
					}
				});
			} else {
				this.importId ??= newImportId();
				const base = { title: this.title.trim(), kind: this.kind, deck: this.deckOptions(), importId: this.importId };
				if (this.tab === 'files') {
					const fileList = await this.uploadAll();
					started = await submitImport(mediaApi, {
						...base,
						source: 'file',
						files: fileList,
						format: this.format,
						options: { ruby: this.ruby, cutAudio: this.cutAudio }
					});
				} else if (this.tab === 'url') {
					started = await submitImport(mediaApi, { ...base, source: 'url', url: this.url.trim() });
				} else {
					started = await submitImport(mediaApi, { ...base, source: 'paste', text: this.text });
				}
			}
			this.watch.show(started);
		} catch {
			// toast shown by the client; the form stays so the user can retry with the same id
			this.failed = true;
		} finally {
			this.busy = false;
			this.upload = null;
		}
	}

	reset(): void {
		this.watch.clear();
		this.failed = false;
		this.importId = null;
		this.uploaded = null;
		this.files = [];
		this.audio = {};
	}

	isRunning(): boolean {
		return isRunning(this.watch.job);
	}
}
