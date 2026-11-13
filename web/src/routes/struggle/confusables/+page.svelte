<script lang="ts">
	import { CONFUSABLE_STATUSES, ConfusablesPage, noteName } from '../../../lib/state/struggle.svelte';
	import { tg, tgEnum } from '../messages';

	const vm = new ConfusablesPage();
</script>

<h1>{tg('st.confusables')}</h1>
<p>
	<a href="/struggle">{tg('st.back')}</a> ·
	<a class="kb-btn" href="/review?drill=confusables">{tg('st.drillConfusables')}</a>
	<small>{tg('st.drillNote')}</small>
</p>
<label>
	{tg('st.filter')}
	<select bind:value={vm.status}>
		{#each CONFUSABLE_STATUSES as s (s)}
			<option value={s}>{tgEnum('st.status', s)}</option>
		{/each}
	</select>
</label>

{#if !vm.loaded}
	<p>{tg('st.loading')}</p>
{:else if vm.pairs.length === 0}
	<p>{tg('st.confusablesNone')}</p>
{:else}
	<ul>
		{#each vm.pairs as p (`${p.noteAId}/${p.noteBId}/${p.reason}`)}
			<li>
				<strong lang="ja">{noteName(p.noteA)}</strong> ⇄ <strong lang="ja">{noteName(p.noteB)}</strong>
				<small>{tgEnum('st.reason', p.reason)} · {tgEnum('st.status', p.status)}</small>
				{#if p.status !== 'confirmed'}
					<button class="kb-btn" type="button" disabled={vm.busy} onclick={() => vm.decide(p, 'confirmed')}>
						{tg('st.confirm')}
					</button>
				{/if}
				{#if p.status !== 'rejected'}
					<button class="kb-btn kb-btn-quiet" type="button" disabled={vm.busy} onclick={() => vm.decide(p, 'rejected')}>
						{tg('st.reject')}
					</button>
				{/if}
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
