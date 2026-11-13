<script lang="ts">
	// Bulk actions on the selection. Every action is a dry run first ("Check": what would change), then an explicit
	// Apply. Sits as a raised bar above the table while something is selected.
	import Banner from '../../lib/ui/Banner.svelte';
	import Button from '../../lib/ui/Button.svelte';
	import Select from '../../lib/ui/Select.svelte';
	import TextField from '../../lib/ui/TextField.svelte';
	import { tc, type CardsMessageKey } from '../../lib/cards/messages';
	import { BULK_KINDS, BulkBarState, type Deck, type Selector } from '../../lib/state/cards.svelte';
	import { tb } from './messages';

	interface Props {
		selector: Selector;
		scopeText: string;
		decks: Deck[];
		canSelectMatching: boolean;
		onselectmatching: () => void;
		onclear: () => void;
		onapplied: () => void;
	}
	let { selector, scopeText, decks, canSelectMatching, onselectmatching, onclear, onapplied }: Props = $props();

	const s = new BulkBarState(
		() => selector,
		() => onapplied()
	);
	const busy = $derived(s.phase === 'checking' || s.phase === 'applying');
	const kinds = BULK_KINDS.map((k) => ({ value: k, label: tc(`bulk.action.${k}` as CardsMessageKey) }));
	const deckOptions = $derived([{ value: '', label: tc('bulk.deckPick') }, ...decks.map((d) => ({ value: d.id, label: d.name }))]);
	let flagText = $state('0');
	$effect(() => {
		const n = Number(flagText);
		s.params.flag = Number.isFinite(n) ? n : 0;
	});
</script>

<section class="bulk" aria-label={tc('bulk.title')}>
	<div class="scope">
		<p><strong>{scopeText}</strong></p>
		<div class="scope-actions">
			{#if canSelectMatching}<Button size="sm" variant="quiet" onclick={onselectmatching}>{tb('br.selectMatching')}</Button>{/if}
			<Button size="sm" variant="quiet" icon="close" onclick={onclear}>{tb('br.clear')}</Button>
		</div>
	</div>
	<div class="form">
		<div class="kind"><Select label={tb('br.bulk')} options={kinds} bind:value={s.kind} disabled={busy} /></div>
		{#if s.kind === 'add_tags' || s.kind === 'remove_tags'}
			<div class="param"><TextField label={tc('bulk.tags')} bind:value={s.params.tags} /></div>
		{:else if s.kind === 'move'}
			<div class="param"><Select label={tc('bulk.deck')} options={deckOptions} bind:value={s.params.deckId} /></div>
		{:else if s.kind === 'flag'}
			<div class="param"><TextField label={tc('bulk.flag')} type="number" min="0" max="7" bind:value={flagText} /></div>
		{:else if s.kind === 'reschedule'}
			<div class="param"><TextField label={tc('bulk.dueAt')} type="datetime-local" bind:value={s.params.dueLocal} /></div>
		{/if}
		{#if s.phase === 'confirm' || s.phase === 'applying'}
			<Button variant={s.kind === 'delete' ? 'danger' : 'primary'} loading={s.phase === 'applying'} onclick={() => s.apply()}>
				{tb('br.apply')}
			</Button>
			<Button variant="quiet" disabled={busy} onclick={() => s.reset()}>{tc('bulk.cancel')}</Button>
		{:else}
			<Button variant="outline" loading={s.phase === 'checking'} onclick={() => s.check()}>{tb('br.check')}</Button>
		{/if}
	</div>
	<div aria-live="polite">
		{#if (s.phase === 'confirm' || s.phase === 'applying') && s.dry}
			<Banner tone={s.kind === 'delete' ? 'warning' : 'info'}>
				{tc('bulk.dryRun', { matched: s.dry.matched, changed: s.dry.changed, skipped: s.dry.skipped })}
				{#if s.kind === 'delete'}{' '}{tc('bulk.deleteWarn')}{/if}
			</Banner>
		{/if}
		{#if s.phase === 'done' && s.result}
			<Banner tone="success">{tc('bulk.result', { matched: s.result.matched, changed: s.result.changed, skipped: s.result.skipped })}</Banner>
		{/if}
		{#if s.error}<Banner tone="error">{s.error}</Banner>{/if}
	</div>
</section>

<style>
	.bulk {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		padding: var(--kb-space-4);
		border: 1px solid var(--kb-color-border);
		border-radius: var(--kb-radius-lg);
		background: var(--kb-color-surface-raised);
		box-shadow: var(--kb-elevation-2);
	}
	.scope {
		display: flex;
		flex-wrap: wrap;
		align-items: center;
		justify-content: space-between;
		gap: var(--kb-space-2);
	}
	.scope-actions {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-1);
	}
	.form {
		display: flex;
		flex-wrap: wrap;
		align-items: flex-end;
		gap: var(--kb-space-3);
	}
	.kind {
		flex: 1 1 12rem;
		max-width: 16rem;
	}
	.param {
		flex: 1 1 14rem;
		max-width: 22rem;
	}
</style>
