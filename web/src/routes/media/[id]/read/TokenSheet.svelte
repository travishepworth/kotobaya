<script lang="ts">
	// A tapped word: its entry and status, Mine / Known / Ignore, and — folded away — fixing the parse.
	import Button from '../../../../lib/ui/Button.svelte';
	import Dialog from '../../../../lib/ui/Dialog.svelte';
	import DictionaryLink from '../../../../lib/ui/DictionaryLink.svelte';
	import Select from '../../../../lib/ui/Select.svelte';
	import Skeleton from '../../../../lib/ui/Skeleton.svelte';
	import TextField from '../../../../lib/ui/TextField.svelte';
	import { tr } from '../../../../lib/reader/messages';
	import { STATUS_ACTIONS, STATUS_VALUE, showEdit, TokenPopupState, type Sentence } from '../../../../lib/state/reader.svelte';

	interface Props {
		sentence: Sentence;
		tokenIndex: number;
		onclose: () => void;
		onoverride: () => void;
		onstatus: (seq: number) => void;
	}
	let { sentence, tokenIndex, onclose, onoverride, onstatus }: Props = $props();
	const vm = new TokenPopupState(
		() => sentence,
		() => tokenIndex,
		() => ({ onoverride, onstatus })
	);
	let open = $state(true);
	let splitText = $state('1');
	$effect(() => {
		const n = Number(splitText);
		if (Number.isFinite(n)) vm.splitOffset = n;
	});
</script>

<Dialog bind:open title={vm.token?.surface ?? tr('rd.entry')} {onclose}>
	{#if vm.token}
		<div class="sheet">
			{#if vm.token.reading && vm.token.reading !== vm.token.surface}<p class="reading" lang="ja">{vm.token.reading}</p>{/if}
			{#if vm.token.seq == null}
				<p class="muted">{tr('rd.noEntry')}</p>
			{:else if !vm.entry}
				<Skeleton shape="text" count={3} />
			{:else}
				<p class="head"><span class="hw" lang="ja">{vm.entry.headword}</span><span class="status">{tr(`rd.status.${vm.entry.status}`)}</span></p>
				<ol class="senses">
					{#each vm.entry.senses.slice(0, 4) as s, i (i)}<li>{s.gloss.map((g) => g.text).join('; ')}</li>{/each}
				</ol>
				<div class="acts">
					<Button variant="primary" icon="plus" onclick={() => vm.mine()}>{tr('rd.mine')}</Button>
					{#each STATUS_ACTIONS as k (k)}
						<Button variant="outline" size="sm" onclick={() => vm.mark(STATUS_VALUE[k])}>{tr(k)}</Button>
					{/each}
					<DictionaryLink look="text" seq={vm.entry.seq} />
				</div>
			{/if}

			<details class="fix">
				<summary>{tr('rd.fix')}</summary>
				<div class="fix-body">
					<Select
						label={tr('rd.scope')}
						bind:value={vm.scope}
						options={[
							{ value: 'sentence', label: tr('rd.scope.sentence') },
							{ value: 'media', label: tr('rd.scope.media') },
							{ value: 'global', label: tr('rd.scope.global') }
						]}
					/>
					<div class="line">
						<TextField label={tr('rd.splitAt')} inputmode="numeric" bind:value={splitText} />
						<Button variant="outline" disabled={!vm.splitPreview} onclick={() => vm.save(vm.splitPreview)}>{tr('rd.split')}</Button>
					</div>
					<p class="muted" lang="ja">{showEdit(vm.splitPreview) || tr('rd.cannotSplit')}</p>
					<div class="line">
						<p class="muted" lang="ja">{showEdit(vm.joinPreview) || tr('rd.noNext')}</p>
						<Button variant="outline" disabled={!vm.joinPreview} onclick={() => vm.save(vm.joinPreview)}>{tr('rd.join')}</Button>
					</div>
					<form
						class="line"
						onsubmit={(e) => {
							e.preventDefault();
							void vm.search();
						}}
					>
						<TextField label={tr('rd.change')} type="search" icon="search" lang="ja" placeholder={tr('rd.search')} bind:value={vm.query} />
						<Button variant="outline" type="submit">{tr('rd.change')}</Button>
					</form>
					{#if vm.results}
						{#if vm.results.length === 0}<p class="muted">{tr('rd.noResults')}</p>{/if}
						<ul class="results">
							{#each vm.results as r (r.seq)}
								<li>
									<button type="button" title={tr('rd.pick')} onclick={() => vm.pick(r)}>
										<span class="rh" lang="ja">{r.headword}</span>
										<span class="muted">{r.glosses.slice(0, 2).join('; ')}</span>
									</button>
								</li>
							{/each}
						</ul>
					{/if}
				</div>
			</details>
		</div>
	{/if}
</Dialog>

<style>
	.sheet {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		color: var(--kb-color-text);
	}
	.reading {
		font: var(--kb-type-reading);
		color: var(--kb-color-text-muted);
	}
	.head {
		display: flex;
		align-items: baseline;
		gap: var(--kb-space-3);
	}
	.hw {
		font: var(--kb-type-title);
	}
	.status {
		padding: 1px var(--kb-space-2);
		border-radius: var(--kb-radius-full);
		background: var(--kb-color-surface-sunken);
		font: var(--kb-type-caption);
		color: var(--kb-color-text-muted);
	}
	.senses {
		margin: 0;
		padding-left: var(--kb-space-5);
		font: var(--kb-type-body);
	}
	.acts {
		display: flex;
		flex-wrap: wrap;
		gap: var(--kb-space-2);
		align-items: center;
	}
	.muted {
		font: var(--kb-type-body-sm);
		color: var(--kb-color-text-muted);
	}
	.fix {
		border-top: 1px solid var(--kb-color-border);
		padding-top: var(--kb-space-2);
	}
	.fix summary {
		display: flex;
		align-items: center;
		min-height: var(--kb-size-touch-min);
		font: var(--kb-type-label);
		cursor: pointer;
	}
	.fix-body {
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
	}
	.line {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: end;
		gap: var(--kb-space-2);
	}
	.results {
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.results button {
		display: flex;
		flex-wrap: wrap;
		align-items: baseline;
		gap: var(--kb-space-2);
		width: 100%;
		min-height: var(--kb-size-touch-min);
		padding: var(--kb-space-2);
		border: 0;
		border-radius: var(--kb-radius-sm);
		background: none;
		color: inherit;
		text-align: start;
		cursor: pointer;
	}
	.results button:hover {
		background: var(--kb-color-surface-hover);
	}
	.rh {
		font: var(--kb-type-title-sm);
	}
</style>
