<script lang="ts">
	import { OverrideListState, scopeOf } from '../state/reader.svelte';
	import { tr } from './messages';

	let { mediaId, version }: { mediaId: string; version: number } = $props();

	const vm = new OverrideListState(
		() => mediaId,
		() => version
	);
</script>

<section>
	<h2>{tr('rd.overrides')}</h2>
	{#if vm.items.length === 0}
		<p>{tr('rd.overrides.none')}</p>
	{:else}
		<ul>
			{#each vm.items as o (o.id)}
				<li>
					<span lang="ja">{o.matchText}</span>
					→ <span lang="ja">{o.tokens.map((t) => t.surface).join(' | ')}</span>
					({tr(`rd.overrides.scope.${scopeOf(o)}`)})
					<button type="button" onclick={() => vm.remove(o)}>{tr('rd.overrides.delete')}</button>
				</li>
			{/each}
		</ul>
	{/if}
</section>
