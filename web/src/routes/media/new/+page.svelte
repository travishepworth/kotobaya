<script lang="ts">
	import { onDestroy } from 'svelte';
	import { apiConfig } from '../../../lib/api/client';
	import {
		BlobsApi,
		JobsApi,
		MediaApi,
		MediaImportRequestFormatEnum,
		MediaImportRequestOptionsRubyEnum,
		MediaKind,
		PutBlobKindEnum
	} from '../../../lib/api/generated';
	import type { Job, MediaDeckOptions, MediaImportFile } from '../../../lib/api/generated';
	import { openEventStream } from '../../../lib/events';
	import { sha256Hex } from '../../import/counters';
	import { newImportId, submitImport } from '../../../lib/media/import';
	import { tm } from '../../../lib/media/messages';
	import type { MediaMessageKey } from '../../../lib/media/messages';

	type Tab = 'files' | 'url' | 'paste' | 'jpdb';
	const tabs: Tab[] = ['files', 'url', 'paste', 'jpdb'];
	const kinds = Object.values(MediaKind);
	const formats = Object.values(MediaImportRequestFormatEnum);

	const blobsApi = new BlobsApi(apiConfig);
	const jobsApi = new JobsApi(apiConfig);
	const mediaApi = new MediaApi(apiConfig);

	let tab = $state<Tab>('files');
	let title = $state('');
	let kind = $state<MediaKind>(MediaKind.Other);
	let files = $state<File[]>([]);
	let audio = $state<Record<number, File | undefined>>({});
	let format = $state<MediaImportRequestFormatEnum>(MediaImportRequestFormatEnum.Auto);
	let ruby = $state<MediaImportRequestOptionsRubyEnum>(MediaImportRequestOptionsRubyEnum.Strip);
	let cutAudio = $state(false);
	let url = $state('');
	let text = $state('');
	let jpdbId = $state('');
	let priority = $state(5);
	let perDay = $state('');
	let enabled = $state(true);

	let busy = $state(false);
	let upload = $state<{ done: number; total: number } | null>(null);
	let job = $state<Job | null>(null);
	let failed = $state(false);
	let closeStream: (() => void) | null = null;
	// Minted once per user action; kept across retries, dropped by reset().
	let importId: string | null = null;
	// Hashes already uploaded, so a retry does not re-read and re-send files.
	let uploaded: MediaImportFile[] | null = null;

	// Mirrors the worker's normalize_deck_id: full jpdb URL or `<kind>/<id>`; a bare number is rejected.
	function normalizeDeckId(raw: string): string | null {
		const m = /^(?:https?:\/\/(?:www\.)?jpdb\.io)?\/?([a-z][a-z-]*)\/(\d+)(?:[/?#].*)?$/i.exec(
			raw.trim()
		);
		if (!m || ['vocabulary', 'kanji', 'api', 'deck'].includes(m[1].toLowerCase())) return null;
		return `${m[1].toLowerCase()}/${m[2]}`;
	}
	const deckId = $derived(normalizeDeckId(jpdbId));

	function isRunning(j: Job | null): boolean {
		return j !== null && (j.status === 'queued' || j.status === 'running');
	}

	const result = $derived<Record<string, unknown> | null>(
		job && job.status === 'succeeded' ? (job.result ?? null) : null
	);
	const mediaId = $derived.by(() => {
		const fromResult = result?.['media_id'];
		if (typeof fromResult === 'string') return fromResult;
		const fromPayload = (job?.payload as Record<string, unknown> | undefined)?.['media_id'];
		return typeof fromPayload === 'string' ? fromPayload : null;
	});
	const unmappedCount = $derived.by(() => {
		const u = result?.['unmapped'];
		return Array.isArray(u) ? u.length : typeof u === 'number' ? u : 0;
	});

	function stop(): void {
		closeStream?.();
		closeStream = null;
	}
	onDestroy(stop);

	async function refresh(id: string): Promise<void> {
		try {
			job = await jobsApi.getJob({ jobId: id });
			if (!isRunning(job)) stop();
		} catch {
			// toast shown by the client
		}
	}

	function follow(id: string): void {
		stop();
		closeStream = openEventStream({
			types: ['job.updated'],
			onEvent: (e) => {
				if (e.type === 'job.updated' && e.data['id'] === id) void refresh(id);
			},
			onReconnect: () => void refresh(id)
		});
	}

	async function putFile(f: File): Promise<string> {
		const bytes = await f.arrayBuffer();
		const hash = await sha256Hex(bytes);
		await blobsApi.putBlob({
			kind: PutBlobKindEnum.Upload,
			hash,
			// The generated model named Blob shadows the DOM Blob in this parameter's type.
			body: new Blob([bytes], { type: f.type || 'application/octet-stream' }) as never
		});
		return hash;
	}

	async function uploadAll(): Promise<MediaImportFile[]> {
		if (uploaded) return uploaded;
		const total = files.length + files.filter((_, i) => audio[i]).length;
		let done = 0;
		upload = { done, total };
		const out: MediaImportFile[] = [];
		for (const [i, f] of files.entries()) {
			const blobHash = await putFile(f);
			upload = { done: ++done, total };
			const a = audio[i];
			let audioBlobHash: string | undefined;
			if (a) {
				audioBlobHash = await putFile(a);
				upload = { done: ++done, total };
			}
			out.push({ filename: f.name, blobHash, ...(audioBlobHash ? { audioBlobHash } : {}) });
		}
		uploaded = out;
		return out;
	}

	function deckOptions(): MediaDeckOptions {
		const n = perDay.trim() === '' ? null : Number(perDay);
		return { priority, enabled, newCardsPerDay: n };
	}

	async function start(): Promise<void> {
		if (busy) return;
		busy = true;
		failed = false;
		try {
			let started: Job;
			if (tab === 'jpdb') {
				started = await mediaApi.importJpdbDeck({
					jpdbDeckImportRequest: {
						jpdbId: deckId ?? jpdbId.trim(),
						kind,
						...(title.trim() ? { title: title.trim() } : {}),
						deck: deckOptions()
					}
				});
			} else {
				importId ??= newImportId();
				const base = { title: title.trim(), kind, deck: deckOptions(), importId };
				if (tab === 'files') {
					const fileList = await uploadAll();
					started = await submitImport(mediaApi, {
						...base,
						source: 'file',
						files: fileList,
						format,
						options: { ruby, cutAudio }
					});
				} else if (tab === 'url') {
					started = await submitImport(mediaApi, { ...base, source: 'url', url: url.trim() });
				} else {
					started = await submitImport(mediaApi, { ...base, source: 'paste', text });
				}
			}
			job = started;
			if (isRunning(started)) follow(started.id);
		} catch {
			// toast shown by the client; the form stays so the user can retry with the same id
			failed = true;
		} finally {
			busy = false;
			upload = null;
		}
	}

	function reset(): void {
		stop();
		job = null;
		failed = false;
		importId = null;
		uploaded = null;
		files = [];
		audio = {};
	}

	const canStart = $derived(
		tab === 'jpdb'
			? deckId !== null
			: title.trim() !== '' &&
					(tab === 'files'
						? files.length > 0
						: tab === 'url'
							? url.trim() !== ''
							: text !== '')
	);
</script>

<h1>{tm('new.title')}</h1>

{#if job === null}
	<div role="tablist" aria-label={tm('new.tabs')}>
		{#each tabs as tb (tb)}
			<button
				role="tab"
				type="button"
				class="kb-btn kb-btn-quiet"
				aria-selected={tab === tb}
				disabled={busy}
				onclick={() => (tab = tb)}>{tm(`new.tab.${tb}` as MediaMessageKey)}</button
			>
		{/each}
	</div>
	<form
		onsubmit={(e) => {
			e.preventDefault();
			void start();
		}}
	>
		<p>
			<label
				>{tm('new.mediaTitle')}
				<input lang="ja" bind:value={title} required={tab !== 'jpdb'} /></label
			>
			{#if tab === 'jpdb'}<small>{tm('new.jpdbTitle')}</small>{/if}
		</p>
		<p>
			<label
				>{tm('new.kind')}
				<select bind:value={kind}>
					{#each kinds as k (k)}
						<option value={k}>{tm(`med.kind.${k}` as MediaMessageKey)}</option>
					{/each}
				</select></label
			>
		</p>

		{#if tab === 'files'}
			<p>
				<label
					>{tm('new.files')}
					<input
						type="file"
						multiple
						onchange={(e) => {
							files = [...(e.currentTarget.files ?? [])];
							audio = {};
							uploaded = null;
						}}
					/></label
				>
			</p>
			<p>
				<label
					>{tm('new.format')}
					<select bind:value={format}>
						{#each formats as f (f)}
							<option value={f}>{f === 'auto' ? tm('new.format.auto') : f}</option>
						{/each}
					</select></label
				>
			</p>
			<p>
				<label
					>{tm('new.ruby')}
					<select bind:value={ruby}>
						<option value="strip">{tm('new.ruby.strip')}</option>
						<option value="hint">{tm('new.ruby.hint')}</option>
					</select></label
				>
			</p>
			{#each files as f, i (i)}
				<p>
					<label
						>{tm('new.audio', { name: f.name })}
						<input
							type="file"
							accept="audio/*"
							onchange={(e) => {
								audio[i] = e.currentTarget.files?.[0];
								uploaded = null;
							}}
						/></label
					>
				</p>
			{/each}
			<p>
				<label><input type="checkbox" bind:checked={cutAudio} /> {tm('new.cutAudio')}</label>
			</p>
		{:else if tab === 'url'}
			<p><label>{tm('new.url')} <input type="url" bind:value={url} required /></label></p>
		{:else if tab === 'paste'}
			<p>
				<label
					>{tm('new.text')}
					<textarea lang="ja" rows="10" maxlength="1000000" bind:value={text}></textarea></label
				>
			</p>
		{:else}
			<p><label>{tm('new.jpdbId')} <input bind:value={jpdbId} required /></label></p>
			{#if jpdbId.trim() !== '' && deckId === null}
				<p role="alert">{tm('new.jpdbIdInvalid')}</p>
			{/if}
		{/if}

		<fieldset>
			<legend>{tm('new.deckOptions')}</legend>
			<p>
				<label
					>{tm('new.priority')}
					<input type="number" min="0" max="9" bind:value={priority} /></label
				>
			</p>
			<p>
				<label>{tm('new.perDay')} <input type="number" min="0" bind:value={perDay} /></label>
			</p>
			<p><label><input type="checkbox" bind:checked={enabled} /> {tm('new.enabled')}</label></p>
		</fieldset>

		{#if upload}
			<p role="status">{tm('new.uploading', { done: upload.done, total: upload.total })}</p>
		{/if}
		<button class="kb-btn" type="submit" disabled={!canStart || busy}>
			{failed ? tm('new.retry') : tm('new.start')}
		</button>
	</form>
{:else if isRunning(job)}
	<p role="status">
		{tm('new.progress', { status: job.status, percent: Math.round(job.progress * 100) })}
	</p>
	<progress max="1" value={job.progress}></progress>
	{#if job.progressMessage}<p>{job.progressMessage}</p>{/if}
{:else if job.status === 'succeeded'}
	<p role="status">{tm('new.done')}</p>
	{#if unmappedCount > 0}
		<p>
			{tm('new.unmapped', { count: unmappedCount })}
			<a href="/import/mapping">{tm('new.unmappedLink')}</a>
		</p>
	{/if}
	<p>
		{#if mediaId}<a class="kb-btn" href={`/media/${mediaId}`}>{tm('new.open')}</a>{/if}
		<a href="/media">{tm('new.library')}</a>
		<button class="kb-btn kb-btn-quiet" type="button" onclick={reset}>{tm('new.another')}</button>
	</p>
{:else}
	<p role="alert">
		{job.status === 'failed'
			? tm('new.failed', { error: job.error ?? tm('new.noError') })
			: tm('new.cancelled')}
	</p>
	<p><button class="kb-btn kb-btn-quiet" type="button" onclick={reset}>{tm('new.another')}</button></p>
{/if}
