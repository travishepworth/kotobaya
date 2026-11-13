<script lang="ts">
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import CurveChart from '../../../lib/coverage/CurveChart.svelte';
	import UnknownWords from '../../../lib/coverage/UnknownWords.svelte';
	import { tc } from '../../../lib/coverage/messages';
	import type { CoverageMessageKey } from '../../../lib/coverage/messages';
	import { tm } from '../../../lib/media/messages';
	import type { MediaMessageKey } from '../../../lib/media/messages';
	import { HIDE_CLASSES, MediaCoveragePage } from '../../../lib/state/media.svelte';

	const id = $derived(page.params.id ?? '');
	const vm = new MediaCoveragePage(() => id);

	onMount(() => vm.mount());

	function pickSegment(e: Event): void {
		vm.pickSegment((e.currentTarget as HTMLSelectElement).value);
	}
</script>

{#if vm.missing}
	<p>{tc('cov.notFound')}</p>
	<p><a href="/media">{tc('cov.back')}</a></p>
{:else if !vm.media}
	<p>{tc('cov.loading')}</p>
{:else}
	<header>
		<h1 lang="ja">{vm.media.title}</h1>
		<p>
			{tc('cov.meta', {
				kind: tm(`med.kind.${vm.media.kind}` as MediaMessageKey),
				status: tm(`med.parse.${vm.media.parseStatus}` as MediaMessageKey),
				segments: vm.segments.length
			})}
		</p>
	</header>

	{#if vm.media.contentVersion === 0}
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
						checked={vm.filters.hide.includes(c)}
						onchange={(e) => vm.toggle(c, e.currentTarget.checked)}
					/>
					{tc(`cov.hide.${c}` as CoverageMessageKey)}
				</label>
			{/each}
		</fieldset>
		<label>
			{tc('cov.segment')}
			<select value={vm.filters.segmentId ?? ''} onchange={pickSegment}>
				<option value="">{tc('cov.segment.all')}</option>
				{#each vm.segments as s (s.id)}
					<option value={s.id}>{s.title || s.ordinal + 1}</option>
				{/each}
			</select>
		</label>
	</section>

	{#if vm.coverage}
		<section>
			<h2>{tc('cov.summary')}</h2>
			<p class="big">{tc('cov.tokenCoverage')}: {vm.coverage.tokens.knownPct}%</p>
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
						<td>{vm.coverage.unique.total}</td>
						<td>{vm.coverage.unique.known}</td>
						<td>{vm.coverage.unique.learning}</td>
						<td>{vm.coverage.unique.unknown}</td>
					</tr>
					<tr>
						<th>{tc('cov.tokens')}</th>
						<td>{vm.coverage.tokens.total}</td>
						<td>{vm.coverage.tokens.known}</td>
						<td>{vm.coverage.tokens.learning}</td>
						<td>{vm.coverage.tokens.unknown}</td>
					</tr>
				</tbody>
			</table>
			<p>{tc('cov.unmapped')}: {vm.coverage.tokens.unmapped} · {tc('cov.ignored')}: {vm.coverage.ignoredCount}</p>
			{#if vm.coverage.difficulty}
				<p>
					{tc('cov.difficulty')}:
					{tc('cov.difficultyRank', { rank: vm.coverage.difficulty.rank, of: vm.coverage.difficulty.of })},
					{tc('cov.difficultyPer1000', { value: vm.coverage.difficulty.unknownPer1000 })}
				</p>
			{/if}
		</section>

		<section>
			<h2>{tc('cov.curve')}</h2>
			<CurveChart curve={vm.coverage.curve} />
		</section>

		{#if vm.coverage.segments.length > 0}
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
						{#each vm.coverage.segments as s (s.segmentId)}
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

	<UnknownWords deckId={vm.media.id} onchanged={() => vm.reloadCoverage()} />
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
