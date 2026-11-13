<script lang="ts">
	// Bulk toolbar. Every action is first run with dry_run and the counts shown; only a second,
	// explicit click applies it. The bulk call is synchronous, so "progress" is the applying state.
	import { BULK_KINDS, BulkBarState, type Deck, type Selector } from '../state/cards.svelte';
	import { tc, type CardsMessageKey } from './messages';

	let {
		selector,
		scopeText,
		decks,
		onapplied
	}: {
		selector: Selector;
		scopeText: string;
		decks: Deck[];
		onapplied: () => void;
	} = $props();

	const s = new BulkBarState(
		() => selector,
		() => onapplied()
	);
</script>

<section class="bar" aria-label={tc('bulk.title')}>
	<h2>{tc('bulk.title')}</h2>
	<p class="scope">{scopeText}</p>
	<div class="fields">
		<label>
			{tc('bulk.action')}
			<select bind:value={s.kind} disabled={s.phase === 'checking' || s.phase === 'applying'}>
				{#each BULK_KINDS as k (k)}
					<option value={k}>{tc(`bulk.action.${k}` as CardsMessageKey)}</option>
				{/each}
			</select>
		</label>
		{#if s.kind === 'add_tags' || s.kind === 'remove_tags'}
			<label>{tc('bulk.tags')} <input type="text" bind:value={s.params.tags} /></label>
		{:else if s.kind === 'move'}
			<label>
				{tc('bulk.deck')}
				<select bind:value={s.params.deckId}>
					<option value="">{tc('bulk.deckPick')}</option>
					{#each decks as d (d.id)}
						<option value={d.id}>{d.name}</option>
					{/each}
				</select>
			</label>
		{:else if s.kind === 'flag'}
			<label>{tc('bulk.flag')} <input type="number" min="0" max="7" bind:value={s.params.flag} /></label>
		{:else if s.kind === 'reschedule'}
			<label>{tc('bulk.dueAt')} <input type="datetime-local" bind:value={s.params.dueLocal} /></label>
		{/if}
		{#if s.phase === 'idle' || s.phase === 'checking' || s.phase === 'done'}
			<button type="button" class="kb-btn" disabled={s.phase === 'checking'} onclick={() => s.check()}>
				{s.phase === 'checking' ? tc('bulk.checking') : tc('bulk.preview')}
			</button>
		{/if}
	</div>
	<div role="status" aria-live="polite">
		{#if (s.phase === 'confirm' || s.phase === 'applying') && s.dry}
			<p>{tc('bulk.dryRun', { matched: s.dry.matched, changed: s.dry.changed, skipped: s.dry.skipped })}</p>
			{#if s.kind === 'delete'}<p class="warn">{tc('bulk.deleteWarn')}</p>{/if}
			<button type="button" class="kb-btn" disabled={s.phase === 'applying'} onclick={() => s.apply()}>
				{s.phase === 'applying' ? tc('bulk.applying') : tc('bulk.confirm')}
			</button>
			<button type="button" class="kb-btn kb-btn-quiet" disabled={s.phase === 'applying'} onclick={() => s.reset()}>
				{tc('bulk.cancel')}
			</button>
		{/if}
		{#if s.phase === 'done' && s.result}
			<p>{tc('bulk.result', { matched: s.result.matched, changed: s.result.changed, skipped: s.result.skipped })}</p>
		{/if}
		{#if s.error}<p class="warn">{s.error}</p>{/if}
	</div>
</section>

<style>
	.bar {
		border: 1px solid var(--kb-border);
		border-radius: var(--kb-radius);
		padding: var(--kb-space-3);
	}
	h2 {
		margin: 0 0 var(--kb-space-2);
		font-size: 1rem;
	}
	.scope {
		margin: 0 0 var(--kb-space-2);
		color: var(--kb-text-muted);
	}
	.fields {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-3);
		align-items: end;
	}
	label {
		display: grid;
		gap: var(--kb-space-1);
	}
	.warn {
		color: var(--kb-error);
	}
</style>
