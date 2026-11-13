<script lang="ts">
	import { onMount } from 'svelte';
	import { tm } from '../../lib/media/messages';
	import type { MediaMessageKey } from '../../lib/media/messages';
	import { MEDIA_KINDS, MediaListPage, type Media } from '../../lib/state/media.svelte';

	const vm = new MediaListPage();
	onMount(() => void vm.load(false));

	function remove(m: Media): void {
		if (!confirm(tm('med.confirmDelete', { title: m.title }))) return;
		void vm.remove(m);
	}
</script>

<h1>{tm('med.title')}</h1>
<p><a class="kb-btn" href="/media/new">{tm('med.new')}</a></p>

<form
	onsubmit={(e) => {
		e.preventDefault();
		void vm.load(false);
	}}
>
	<label
		>{tm('med.filter.kind')}
		<select bind:value={vm.kind} onchange={() => void vm.load(false)}>
			<option value="">{tm('med.filter.all')}</option>
			{#each MEDIA_KINDS as k (k)}
				<option value={k}>{tm(`med.kind.${k}` as MediaMessageKey)}</option>
			{/each}
		</select></label
	>
	<label>{tm('med.filter.title')} <input type="search" bind:value={vm.q} /></label>
</form>

{#if vm.items.length === 0 && !vm.loading}
	<p>{tm('med.empty')}</p>
{:else}
	<table>
		<thead>
			<tr>
				<th>{tm('med.col.title')}</th><th>{tm('med.col.kind')}</th>
				<th>{tm('med.col.source')}</th><th>{tm('med.col.parse')}</th>
				<th>{tm('med.col.segments')}</th><th>{tm('med.col.actions')}</th>
			</tr>
		</thead>
		<tbody>
			{#each vm.items as m (m.id)}
				<tr>
					<td lang="ja">
						{#if vm.editing === m.id}
							<form
								onsubmit={(e) => {
									e.preventDefault();
									void vm.saveRename(m);
								}}
							>
								<input aria-label={tm('med.titleLabel')} bind:value={vm.draftTitle} />
								<button class="kb-btn" type="submit">{tm('med.save')}</button>
								<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.cancelRename()}
									>{tm('med.cancel')}</button
								>
							</form>
						{:else}
							<a href={`/media/${m.id}`}>{m.title}</a>
						{/if}
					</td>
					<td>{tm(`med.kind.${m.kind}` as MediaMessageKey)}</td>
					<td>{tm(`med.src.${m.source}` as MediaMessageKey)}</td>
					<td>{tm(`med.parse.${m.parseStatus}` as MediaMessageKey)}</td>
					<td>{m.segments?.length ?? 0}</td>
					<td>
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => vm.startRename(m)}
							>{tm('med.rename')}</button
						>
						{#if m.source !== 'jpdb'}
							<button class="kb-btn kb-btn-quiet" type="button" onclick={() => void vm.reparse(m)}
								>{tm('med.reparse')}</button
							>
						{/if}
						<button class="kb-btn kb-btn-quiet" type="button" onclick={() => void remove(m)}
							>{tm('med.delete')}</button
						>
					</td>
				</tr>
			{/each}
		</tbody>
	</table>
{/if}
{#if vm.cursor}
	<button class="kb-btn" type="button" disabled={vm.loading} onclick={() => void vm.load(true)}
		>{tm('med.more')}</button
	>
{/if}

<style>
	table {
		border-collapse: collapse;
		margin-bottom: var(--kb-space-4);
	}
	th,
	td {
		text-align: left;
		padding: var(--kb-space-1) var(--kb-space-3);
		border-bottom: 1px solid var(--kb-border);
	}
</style>
