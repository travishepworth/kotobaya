<script lang="ts">
	import type { Confusable, ConfusableStatus, NoteSummary } from '../../../lib/api/generated';
	import { wordsApi } from '../../../lib/review/api';
	import { pushToast } from '../../../lib/toasts.svelte';
	import { tg, tgEnum } from '../messages';

	let pairs = $state<Confusable[]>([]);
	let cursor = $state<string | null>(null);
	let status = $state<ConfusableStatus>('suggested');
	let loaded = $state(false);
	let busy = $state(false);

	async function load(more: boolean): Promise<void> {
		busy = true;
		try {
			const page = await wordsApi.listConfusables({
				status,
				limit: 50,
				cursor: more ? (cursor ?? undefined) : undefined
			});
			pairs = more ? [...pairs, ...page.items] : page.items;
			cursor = page.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			busy = false;
			loaded = true;
		}
	}

	$effect(() => {
		void status;
		void load(false);
	});

	function name(n: NoteSummary): string {
		return n.fields['word'] ?? n.fields['sentence'] ?? n.kanjiLiteral ?? n.id;
	}

	async function decide(p: Confusable, to: 'confirmed' | 'rejected'): Promise<void> {
		busy = true;
		try {
			await wordsApi.setConfusable({
				noteAId: p.noteAId,
				noteBId: p.noteBId,
				reason: p.reason,
				confusableSet: { status: to }
			});
			// The pair leaves the current filter (or changes status): drop it from the view.
			pairs = pairs.filter(
				(x) => !(x.noteAId === p.noteAId && x.noteBId === p.noteBId && x.reason === p.reason)
			);
			pushToast(tg('st.pairUpdated'), 'info');
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}
</script>

<h1>{tg('st.confusables')}</h1>
<p>
	<a href="/struggle">{tg('st.back')}</a> ·
	<a class="kb-btn" href="/review?drill=confusables">{tg('st.drillConfusables')}</a>
	<small>{tg('st.drillNote')}</small>
</p>
<label>
	{tg('st.filter')}
	<select bind:value={status}>
		{#each ['suggested', 'confirmed', 'rejected'] as s (s)}
			<option value={s}>{tgEnum('st.status', s)}</option>
		{/each}
	</select>
</label>

{#if !loaded}
	<p>{tg('st.loading')}</p>
{:else if pairs.length === 0}
	<p>{tg('st.confusablesNone')}</p>
{:else}
	<ul>
		{#each pairs as p (`${p.noteAId}/${p.noteBId}/${p.reason}`)}
			<li>
				<strong lang="ja">{name(p.noteA)}</strong> ⇄ <strong lang="ja">{name(p.noteB)}</strong>
				<small>{tgEnum('st.reason', p.reason)} · {tgEnum('st.status', p.status)}</small>
				{#if p.status !== 'confirmed'}
					<button class="kb-btn" type="button" disabled={busy} onclick={() => decide(p, 'confirmed')}>
						{tg('st.confirm')}
					</button>
				{/if}
				{#if p.status !== 'rejected'}
					<button class="kb-btn kb-btn-quiet" type="button" disabled={busy} onclick={() => decide(p, 'rejected')}>
						{tg('st.reject')}
					</button>
				{/if}
			</li>
		{/each}
	</ul>
	{#if cursor}
		<button class="kb-btn" type="button" disabled={busy} onclick={() => load(true)}>{tg('st.more')}</button>
	{/if}
{/if}

<style>
	small {
		color: var(--kb-text-muted);
	}
	li {
		margin-bottom: var(--kb-space-2);
	}
</style>
