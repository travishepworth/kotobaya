<script lang="ts">
	// jpdb history import as a stepper: Upload → Import (live progress) → Review (every counter, every unmapped item)
	// → Done. Nothing dropped is hidden.
	import { onDestroy } from 'svelte';
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import FileDrop from '../../lib/ui/FileDrop.svelte';
	import Page from '../../lib/ui/Page.svelte';
	import Panel from '../../lib/ui/Panel.svelte';
	import ProgressBar from '../../lib/ui/ProgressBar.svelte';
	import Stepper from '../../lib/ui/Stepper.svelte';
	import Switch from '../../lib/ui/Switch.svelte';
	import { ImportPage, isRunning } from '../../lib/state/import.svelte';
	import { ti } from './messages';

	const vm = new ImportPage();
	onDestroy(() => vm.dispose());

	const step = $derived(vm.job === null ? 0 : isRunning(vm.job) ? 1 : vm.job.status === 'succeeded' ? 2 : 1);
	const GROUPS: { title: Parameters<typeof ti>[0]; prefix: RegExp }[] = [
		{ title: 'imp.vocab', prefix: /^(vocabulary_|notes_|cards_|word_statuses)/ },
		{ title: 'imp.kanji', prefix: /^kanji_/ },
		{ title: 'imp.reviewsGroup', prefix: /^reviews_/ },
		{ title: 'imp.other', prefix: /^status_events_/ }
	];
	function human(key: string): string {
		const s = key.replace(/^(vocabulary|kanji|reviews)_/, '').replace(/_/g, ' ');
		return s.charAt(0).toUpperCase() + s.slice(1);
	}
	function size(n: number): string {
		return n > 1_000_000 ? `${(n / 1_000_000).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1000))} kB`;
	}
</script>

<svelte:head><title>{ti('imp.title.page')} · Kotobaya</title></svelte:head>

<Page title={ti('imp.title')} lead={ti('imp.intro')} width="measure">
	{#snippet actions()}<Button variant="quiet" iconEnd="chevronRight" href="/import/mapping">{ti('imp.mapping')}</Button>{/snippet}

	<div class="steps">
		<Stepper
			label={ti('imp.steps')}
			steps={[ti('imp.step.upload'), ti('imp.step.import'), ti('imp.step.review'), ti('imp.step.done')]}
			current={step}
		/>
	</div>

	{#if vm.job === null}
		<form
			class="upload"
			onsubmit={(e) => {
				e.preventDefault();
				void vm.start();
			}}
		>
			<FileDrop
				title={vm.file ? ti('imp.change') : ti('imp.drop')}
				hint={ti('imp.dropHint')}
				accept=".json,application/json"
				icon="import"
				chosen={vm.file ? ti('imp.chosen', { name: vm.file.name, size: size(vm.file.size) }) : undefined}
				onfiles={(f) => (vm.file = f?.[0] ?? null)}
			/>
			<Panel title={ti('imp.options')}>
				<Switch label={ti('imp.reviews')} bind:checked={vm.importReviews} />
				<Switch label={ti('imp.status')} bind:checked={vm.importWordStatus} />
			</Panel>
			<div class="actions">
				<Button variant="primary" size="lg" type="submit" icon="import" disabled={!vm.file} loading={vm.uploading}>{ti('imp.start')}</Button>
			</div>
		</form>
	{:else if isRunning(vm.job)}
		<Panel>
			<div class="running" role="status">
				<p class="big kb-num">{Math.round(vm.job.progress * 100)}%</p>
				<ProgressBar value={Math.round(vm.job.progress * 100)} max={100} label={ti('imp.step.import')} />
				{#if vm.job.progressMessage}<p class="muted">{vm.job.progressMessage}</p>{/if}
				<p class="muted">{ti('imp.leave')}</p>
			</div>
		</Panel>
	{:else if vm.job.status === 'succeeded' && vm.counters}
		{@const all = [...vm.counters.known, ...vm.counters.other]}
		{@const rest = all.filter((c) => !GROUPS.some((g) => g.prefix.test(c.key)))}
		<div class="result">
			<Banner tone="success" title={ti('imp.done')}>
				{#if vm.unmapped.length === 0 && vm.kanjiUnmapped.length === 0}{ti('imp.nothingDropped')}{/if}
			</Banner>
			{#each GROUPS as g (g.title)}
				{@const items = all.filter((c) => g.prefix.test(c.key))}
				{#if items.length > 0}
					<Panel title={ti(g.title)}>
						<dl class="counts">
							{#each items as c (c.key)}
								<div><dt>{human(c.key)}</dt><dd class="kb-num">{c.value.toLocaleString()}</dd></div>
							{/each}
						</dl>
					</Panel>
				{/if}
			{/each}
			{#if rest.length > 0}
				<Panel title={ti('imp.counter.other')}>
					<dl class="counts">
						{#each rest as c (c.key)}<div><dt>{human(c.key)}</dt><dd class="kb-num">{c.value.toLocaleString()}</dd></div>{/each}
					</dl>
				</Panel>
			{/if}

			<Panel title={ti('imp.unmapped', { count: vm.unmapped.length })} flush>
				{#if vm.unmapped.length === 0}
					<p class="none">{ti('imp.none')}</p>
				{:else}
					<div class="table-wrap">
						<table>
							<thead><tr><th>{ti('imp.col.spelling')}</th><th>{ti('imp.col.reading')}</th><th class="end">{ti('imp.col.reviews')}</th><th>{ti('imp.col.vid')}</th></tr></thead>
							<tbody>
								{#each vm.unmapped as u, i (i)}
									<tr><td lang="ja">{u.spelling}</td><td lang="ja">{u.reading}</td><td class="end kb-num">{u.review_count}</td><td class="muted">{u.jpdb_vid}</td></tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			</Panel>

			<Panel title={ti('imp.kanjiUnmapped', { count: vm.kanjiUnmapped.length })} flush>
				{#if vm.kanjiUnmapped.length === 0}
					<p class="none">{ti('imp.none')}</p>
				{:else}
					<div class="table-wrap">
						<table>
							<thead><tr><th>{ti('imp.col.character')}</th><th>{ti('imp.col.kind')}</th><th class="end">{ti('imp.col.reviews')}</th></tr></thead>
							<tbody>
								{#each vm.kanjiUnmapped as k, i (i)}
									<tr><td lang="ja">{k.character}</td><td>{k.card_kind}</td><td class="end kb-num">{k.review_count}</td></tr>
								{/each}
							</tbody>
						</table>
					</div>
				{/if}
			</Panel>
			<div class="actions"><Button variant="outline" onclick={() => vm.reset()}>{ti('imp.reset')}</Button></div>
		</div>
	{:else}
		<Banner tone="error">
			{vm.job.status === 'failed' ? ti('imp.failed', { error: vm.job.error ?? ti('imp.noError') }) : ti('imp.cancelled')}
			{#snippet action()}<Button size="sm" variant="outline" onclick={() => vm.reset()}>{ti('imp.reset')}</Button>{/snippet}
		</Banner>
	{/if}
</Page>

<style>
	.steps {
		margin-bottom: var(--kb-space-6);
	}
	.upload,
	.result {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-5);
	}
	.actions {
		display: flex;
		justify-content: flex-end;
	}
	.running {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.big {
		font: var(--kb-type-display);
	}
	.muted {
		color: var(--kb-color-text-muted);
	}
	.counts {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(min(100%, 11rem), 1fr));
		gap: var(--kb-space-3) var(--kb-space-4);
		margin: 0;
	}
	dt {
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	dd {
		margin: 0;
		font: var(--kb-type-title);
		font-weight: 600;
	}
	.none {
		padding: 0 var(--kb-space-5) var(--kb-space-3);
		color: var(--kb-color-text-muted);
	}
	.table-wrap {
		overflow-x: auto;
	}
	table {
		width: 100%;
		border-collapse: collapse;
		font: var(--kb-type-body-sm);
	}
	th,
	td {
		padding: var(--kb-space-2) var(--kb-space-5);
		border-bottom: 1px solid var(--kb-color-border);
		text-align: start;
		white-space: nowrap;
	}
	th {
		font: var(--kb-type-label);
		color: var(--kb-color-text-muted);
	}
	td:lang(ja) {
		font: var(--kb-type-body);
	}
	.end {
		text-align: end;
	}
</style>
