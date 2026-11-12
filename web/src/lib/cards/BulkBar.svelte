<script lang="ts">
	// Bulk toolbar. Every action is first run with dry_run and the counts shown; only a second,
	// explicit click applies it. The bulk call is synchronous, so "progress" is the applying state.
	import type { CardBulkResult, Deck } from '../api/generated';
	import { ResponseError, problemMessage, readProblem } from '../api/client';
	import { cardsApi } from './api';
	import { tc, type CardsMessageKey } from './messages';
	import { BULK_KINDS, buildRequest, type BulkKind, type BulkParams, type Selector } from './model';

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

	let kind = $state<BulkKind>('add_tags');
	let params = $state<BulkParams>({ tags: '', deckId: '', flag: 0, dueLocal: '' });
	type Phase = 'idle' | 'checking' | 'confirm' | 'applying' | 'done';
	let phase = $state<Phase>('idle');
	let dry = $state<CardBulkResult | null>(null);
	let result = $state<CardBulkResult | null>(null);
	let error = $state<string | null>(null);

	// Any change of action, parameters or selection invalidates a dry run that was already shown.
	$effect(() => {
		void kind;
		void params.tags;
		void params.deckId;
		void params.flag;
		void params.dueLocal;
		void selector;
		reset();
	});

	function reset(): void {
		phase = 'idle';
		dry = null;
		result = null;
		error = null;
	}

	async function run(dryRun: boolean): Promise<CardBulkResult | null> {
		const req = buildRequest(kind, params, selector, dryRun);
		if (!req) {
			error = tc('bulk.invalidInput');
			return null;
		}
		try {
			return req.schedule
				? await cardsApi.bulkScheduleCards({ cardBulkScheduleRequest: req.body })
				: await cardsApi.bulkCards({ cardBulkRequest: req.body });
		} catch (e) {
			const detail =
				e instanceof ResponseError
					? problemMessage(await readProblem(e.response), e.response.status)
					: String(e);
			error = tc('bulk.failed', { detail });
			return null;
		}
	}

	async function check(): Promise<void> {
		error = null;
		phase = 'checking';
		dry = await run(true);
		phase = dry ? 'confirm' : 'idle';
	}

	async function apply(): Promise<void> {
		phase = 'applying';
		result = await run(false);
		phase = result ? 'done' : 'confirm';
		if (result) onapplied();
	}
</script>

<section class="bar" aria-label={tc('bulk.title')}>
	<h2>{tc('bulk.title')}</h2>
	<p class="scope">{scopeText}</p>
	<div class="fields">
		<label>
			{tc('bulk.action')}
			<select bind:value={kind} disabled={phase === 'checking' || phase === 'applying'}>
				{#each BULK_KINDS as k (k)}
					<option value={k}>{tc(`bulk.action.${k}` as CardsMessageKey)}</option>
				{/each}
			</select>
		</label>
		{#if kind === 'add_tags' || kind === 'remove_tags'}
			<label>{tc('bulk.tags')} <input type="text" bind:value={params.tags} /></label>
		{:else if kind === 'move'}
			<label>
				{tc('bulk.deck')}
				<select bind:value={params.deckId}>
					<option value="">{tc('bulk.deckPick')}</option>
					{#each decks as d (d.id)}
						<option value={d.id}>{d.name}</option>
					{/each}
				</select>
			</label>
		{:else if kind === 'flag'}
			<label>{tc('bulk.flag')} <input type="number" min="0" max="7" bind:value={params.flag} /></label>
		{:else if kind === 'reschedule'}
			<label>{tc('bulk.dueAt')} <input type="datetime-local" bind:value={params.dueLocal} /></label>
		{/if}
		{#if phase === 'idle' || phase === 'checking' || phase === 'done'}
			<button type="button" class="kb-btn" disabled={phase === 'checking'} onclick={check}>
				{phase === 'checking' ? tc('bulk.checking') : tc('bulk.preview')}
			</button>
		{/if}
	</div>
	<div role="status" aria-live="polite">
		{#if (phase === 'confirm' || phase === 'applying') && dry}
			<p>{tc('bulk.dryRun', { matched: dry.matched, changed: dry.changed, skipped: dry.skipped })}</p>
			{#if kind === 'delete'}<p class="warn">{tc('bulk.deleteWarn')}</p>{/if}
			<button type="button" class="kb-btn" disabled={phase === 'applying'} onclick={apply}>
				{phase === 'applying' ? tc('bulk.applying') : tc('bulk.confirm')}
			</button>
			<button type="button" class="kb-btn kb-btn-quiet" disabled={phase === 'applying'} onclick={reset}>
				{tc('bulk.cancel')}
			</button>
		{/if}
		{#if phase === 'done' && result}
			<p>{tc('bulk.result', { matched: result.matched, changed: result.changed, skipped: result.skipped })}</p>
		{/if}
		{#if error}<p class="warn">{error}</p>{/if}
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
