<script lang="ts">
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { apiConfig } from '../../../lib/api/client';
	import { MediaApi } from '../../../lib/api/generated';
	import type { Coverage, Media } from '../../../lib/api/generated';
	import { ResponseError } from '../../../lib/api/client';
	import CurveChart from '../../../lib/coverage/CurveChart.svelte';
	import UnknownWords from '../../../lib/coverage/UnknownWords.svelte';
	import { HIDE_CLASSES, loadFilters, saveFilters } from '../../../lib/coverage/filters';
	import type { HideClass } from '../../../lib/coverage/filters';
	import { tc } from '../../../lib/coverage/messages';
	import type { CoverageMessageKey } from '../../../lib/coverage/messages';
	import { tm } from '../../../lib/media/messages';
	import type { MediaMessageKey } from '../../../lib/media/messages';

	const api = new MediaApi(apiConfig);
	const id = $derived(page.params.id ?? '');

	let media = $state<Media | null>(null);
	let coverage = $state<Coverage | null>(null);
	let missing = $state(false);
	let filters = $state(loadFilters());
	let ready = $state(false);

	async function loadMedia(mediaId: string): Promise<void> {
		try {
			media = await api.getMedia({ mediaId });
		} catch (e) {
			if (e instanceof ResponseError && (e.response.status === 404 || e.response.status === 410)) {
				missing = true;
			}
		}
	}

	let gen = 0;
	async function loadCoverage(): Promise<void> {
		const mine = ++gen;
		try {
			const c = await api.getMediaCoverage({
				mediaId: id,
				segmentId: filters.segmentId ?? undefined,
				hide: new Set(filters.hide)
			});
			if (mine === gen) coverage = c;
		} catch {
			/* toast shown */
		}
	}

	onMount(() => {
		filters = loadFilters();
		ready = true;
	});

	$effect(() => {
		void loadMedia(id);
	});

	$effect(() => {
		if (!ready) return;
		void filters.hide;
		void filters.segmentId;
		void loadCoverage();
	});

	function toggle(c: HideClass, on: boolean): void {
		const hide = on ? [...filters.hide, c] : filters.hide.filter((x) => x !== c);
		filters = { ...filters, hide: HIDE_CLASSES.filter((x) => hide.includes(x)) };
		saveFilters(filters);
	}

	function pickSegment(e: Event): void {
		const v = (e.currentTarget as HTMLSelectElement).value;
		filters = { ...filters, segmentId: v === '' ? null : v };
		saveFilters(filters);
	}

	const segments = $derived(media?.segments ?? []);
	// A stored segment from another media is ignored by the server with 404; drop it.
	$effect(() => {
		if (media && filters.segmentId && !segments.some((s) => s.id === filters.segmentId)) {
			filters = { ...filters, segmentId: null };
		}
	});
</script>

{#if missing}
	<p>{tc('cov.notFound')}</p>
	<p><a href="/media">{tc('cov.back')}</a></p>
{:else if !media}
	<p>{tc('cov.loading')}</p>
{:else}
	<header>
		<h1 lang="ja">{media.title}</h1>
		<p>
			{tc('cov.meta', {
				kind: tm(`med.kind.${media.kind}` as MediaMessageKey),
				status: tm(`med.parse.${media.parseStatus}` as MediaMessageKey),
				segments: segments.length
			})}
		</p>
	</header>

	{#if media.contentVersion === 0}
		<p>{tc('cov.notParsed')}</p>
	{/if}

	<section>
		<h2>{tc('cov.filters')}</h2>
		<fieldset>
			<legend>{tc('cov.hide')}</legend>
			{#each HIDE_CLASSES as c (c)}
				<label>
					<input
						type="checkbox"
						checked={filters.hide.includes(c)}
						onchange={(e) => toggle(c, e.currentTarget.checked)}
					/>
					{tc(`cov.hide.${c}` as CoverageMessageKey)}
				</label>
			{/each}
		</fieldset>
		<label>
			{tc('cov.segment')}
			<select value={filters.segmentId ?? ''} onchange={pickSegment}>
				<option value="">{tc('cov.segment.all')}</option>
				{#each segments as s (s.id)}
					<option value={s.id}>{s.title || s.ordinal + 1}</option>
				{/each}
			</select>
		</label>
	</section>

	{#if coverage}
		<section>
			<h2>{tc('cov.summary')}</h2>
			<p class="big">{tc('cov.tokenCoverage')}: {coverage.tokens.knownPct}%</p>
			<table>
				<thead>
					<tr>
						<th></th>
						<th>{tc('cov.total')}</th>
						<th>{tc('cov.known')}</th>
						<th>{tc('cov.learning')}</th>
						<th>{tc('cov.unknown')}</th>
					</tr>
				</thead>
				<tbody>
					<tr>
						<th>{tc('cov.unique')}</th>
						<td>{coverage.unique.total}</td>
						<td>{coverage.unique.known}</td>
						<td>{coverage.unique.learning}</td>
						<td>{coverage.unique.unknown}</td>
					</tr>
					<tr>
						<th>{tc('cov.tokens')}</th>
						<td>{coverage.tokens.total}</td>
						<td>{coverage.tokens.known}</td>
						<td>{coverage.tokens.learning}</td>
						<td>{coverage.tokens.unknown}</td>
					</tr>
				</tbody>
			</table>
			<p>{tc('cov.unmapped')}: {coverage.tokens.unmapped} · {tc('cov.ignored')}: {coverage.ignoredCount}</p>
			{#if coverage.difficulty}
				<p>
					{tc('cov.difficulty')}:
					{tc('cov.difficultyRank', { rank: coverage.difficulty.rank, of: coverage.difficulty.of })},
					{tc('cov.difficultyPer1000', { value: coverage.difficulty.unknownPer1000 })}
				</p>
			{/if}
		</section>

		<section>
			<h2>{tc('cov.curve')}</h2>
			<CurveChart curve={coverage.curve} />
		</section>

		{#if coverage.segments.length > 0}
			<section>
				<h2>{tc('cov.segments')}</h2>
				<table>
					<thead>
						<tr>
							<th>{tc('cov.col.segment')}</th>
							<th>{tc('cov.col.unique')}</th>
							<th>{tc('cov.col.unknown')}</th>
							<th>{tc('cov.col.tokens')}</th>
							<th>{tc('cov.col.known')}</th>
						</tr>
					</thead>
					<tbody>
						{#each coverage.segments as s (s.segmentId)}
							<tr>
								<td lang="ja">{s.title || s.ordinal + 1}</td>
								<td>{s.uniqueTotal}</td>
								<td>{s.uniqueUnknown}</td>
								<td>{s.tokenTotal}</td>
								<td>{s.knownPct}%</td>
							</tr>
						{/each}
					</tbody>
				</table>
			</section>
		{/if}
	{/if}

	<UnknownWords deckId={media.id} onchanged={() => void loadCoverage()} />
{/if}

<style>
	.big {
		font-size: 1.5rem;
	}
	fieldset {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-3);
	}
</style>
