<script lang="ts">
	import { LeechesPage, leechLabel } from '../../../lib/state/struggle.svelte';
	import { tg } from '../messages';

	const vm = new LeechesPage();

	$effect(() => {
		void vm.load(false);
	});
</script>

<h1>{tg('st.leeches')}</h1>
<p>
	<a href="/struggle">{tg('st.back')}</a> ·
	<a class="kb-btn" href="/review?drill=leeches">{tg('st.drillLeeches')}</a>
	<small>{tg('st.drillNote')}</small>
</p>
{#if !vm.loaded}
	<p>{tg('st.loading')}</p>
{:else if vm.cards.length === 0}
	<p>{tg('st.leechesNone')}</p>
{:else}
	<ul>
		{#each vm.cards as c (c.id)}
			<li>
				<span lang="ja">{leechLabel(c)}</span>
				<small>{c.kind} · {tg('st.lapses', { n: c.lapses })}{#if c.suspended} · {tg('st.suspended')}{/if}</small>
			</li>
		{/each}
	</ul>
	{#if vm.cursor}
		<button class="kb-btn" type="button" disabled={vm.busy} onclick={() => vm.load(true)}>{tg('st.more')}</button>
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
