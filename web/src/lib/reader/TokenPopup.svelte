<script lang="ts">
	import { STATUS_ACTIONS, STATUS_VALUE, showEdit, TokenPopupState, type Sentence } from '../state/reader.svelte';
	import { tr } from './messages';

	let {
		sentence,
		tokenIndex,
		onclose,
		onoverride,
		onstatus
	}: {
		sentence: Sentence;
		tokenIndex: number;
		onclose: () => void;
		onoverride: () => void;
		onstatus: (seq: number) => void;
	} = $props();

	const vm = new TokenPopupState(
		() => sentence,
		() => tokenIndex,
		() => ({ onoverride, onstatus })
	);
</script>

{#if vm.token}
	<aside class="popup" aria-label={tr('rd.entry')}>
		<header>
			<strong lang="ja">{vm.token.surface}</strong>
			{#if vm.token.reading && vm.token.reading !== vm.token.surface}<span lang="ja">{vm.token.reading}</span>{/if}
			<button type="button" onclick={onclose}>{tr('rd.close')}</button>
		</header>

		{#if vm.token.seq == null}
			<p>{tr('rd.noEntry')}</p>
		{:else if vm.entry}
			<p><span lang="ja">{vm.entry.headword}</span> · {tr('rd.status')}: {tr(`rd.status.${vm.entry.status}`)}</p>
			<ol>
				{#each vm.entry.senses.slice(0, 4) as s, i (i)}
					<li>{s.gloss.map((g) => g.text).join('; ')}</li>
				{/each}
			</ol>
			<div class="row">
				<button type="button" onclick={() => vm.mine()}>{tr('rd.mine')}</button>
				{#each STATUS_ACTIONS as k (k)}
					<button type="button" onclick={() => vm.mark(STATUS_VALUE[k])}>{tr(k)}</button>
				{/each}
			</div>
		{/if}

		<fieldset>
			<legend>{tr('rd.fix')}</legend>
			<label>
				{tr('rd.scope')}
				<select bind:value={vm.scope}>
					<option value="sentence">{tr('rd.scope.sentence')}</option>
					<option value="media">{tr('rd.scope.media')}</option>
					<option value="global">{tr('rd.scope.global')}</option>
				</select>
			</label>
			<div class="row">
				<label>
					{tr('rd.splitAt')}
					<input type="number" min="1" max={Math.max(1, vm.token.surface.length - 1)} bind:value={vm.splitOffset} />
				</label>
				<button type="button" disabled={!vm.splitPreview} onclick={() => vm.save(vm.splitPreview)}>
					{tr('rd.split')}
				</button>
				<small>{showEdit(vm.splitPreview) || tr('rd.cannotSplit')}</small>
			</div>
			<div class="row">
				<button type="button" disabled={!vm.joinPreview} onclick={() => vm.save(vm.joinPreview)}>
					{tr('rd.join')}
				</button>
				<small>{showEdit(vm.joinPreview) || tr('rd.noNext')}</small>
			</div>
			<form
				class="row"
				onsubmit={(e) => {
					e.preventDefault();
					void vm.search();
				}}
			>
				<input type="search" placeholder={tr('rd.search')} aria-label={tr('rd.change')} bind:value={vm.query} />
				<button type="submit">{tr('rd.change')}</button>
			</form>
			{#if vm.results}
				{#if vm.results.length === 0}
					<p>{tr('rd.noResults')}</p>
				{/if}
				<ul>
					{#each vm.results as r (r.seq)}
						<li>
							<button
								type="button"
								title={tr('rd.pick')}
								onclick={() => vm.pick(r)}
							>
								<span lang="ja">{r.headword}</span> {r.glosses.slice(0, 2).join('; ')}
							</button>
						</li>
					{/each}
				</ul>
			{/if}
		</fieldset>
	</aside>
{/if}

<style>
	.popup {
		border: 1px solid var(--kb-border, #888);
		padding: var(--kb-space-3);
		margin: var(--kb-space-2) 0;
		background: var(--kb-surface, transparent);
	}
	header,
	.row {
		display: flex;
		gap: var(--kb-space-2);
		align-items: center;
		flex-wrap: wrap;
	}
	ul {
		list-style: none;
		padding: 0;
	}
</style>
