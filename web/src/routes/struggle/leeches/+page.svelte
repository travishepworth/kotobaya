<script lang="ts">
	import type { Card } from '../../../lib/api/generated';
	import { wordsApi } from '../../../lib/review/api';
	import { tg } from '../messages';

	let cards = $state<Card[]>([]);
	let cursor = $state<string | null>(null);
	let loaded = $state(false);
	let busy = $state(false);

	async function load(more: boolean): Promise<void> {
		busy = true;
		try {
			const page = await wordsApi.listLeeches({ limit: 50, cursor: more ? (cursor ?? undefined) : undefined });
			const merged = more ? [...cards, ...page.items] : page.items;
			// Cursor paging has no snapshot: de-duplicate by id, last occurrence wins.
			cards = [...new Map(merged.map((c) => [c.id, c])).values()];
			cursor = page.nextCursor;
		} catch {
			/* toast shown */
		} finally {
			busy = false;
			loaded = true;
		}
	}

	$effect(() => {
		void load(false);
	});

	function label(c: Card): string {
		const n = c.note;
		return n?.fields['word'] ?? n?.fields['sentence'] ?? n?.kanjiLiteral ?? c.noteId;
	}
</script>

<h1>{tg('st.leeches')}</h1>
<p>
	<a href="/struggle">{tg('st.back')}</a> ·
	<a class="kb-btn" href="/review?drill=leeches">{tg('st.drillLeeches')}</a>
	<small>{tg('st.drillNote')}</small>
</p>
{#if !loaded}
	<p>{tg('st.loading')}</p>
{:else if cards.length === 0}
	<p>{tg('st.leechesNone')}</p>
{:else}
	<ul>
		{#each cards as c (c.id)}
			<li>
				<span lang="ja">{label(c)}</span>
				<small>{c.kind} · {tg('st.lapses', { n: c.lapses })}{#if c.suspended} · {tg('st.suspended')}{/if}</small>
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
