<script lang="ts">
	import { apiConfig } from '../api/client';
	import { ParsingApi } from '../api/generated';
	import type { ParseOverride } from '../api/generated';
	import { pushToast } from '../toasts.svelte';
	import { tr } from './messages';
	import { scopeOf } from './overrides';

	let { mediaId, version }: { mediaId: string; version: number } = $props();

	const parsing = new ParsingApi(apiConfig);
	let items = $state<ParseOverride[]>([]);

	async function load(): Promise<void> {
		try {
			items = (await parsing.listParseOverrides({ mediaId, limit: 200 })).items;
		} catch {
			/* toast shown */
		}
	}

	$effect(() => {
		void version;
		void mediaId;
		void load();
	});

	async function remove(o: ParseOverride): Promise<void> {
		try {
			await parsing.deleteParseOverride({ overrideId: o.id });
			items = items.filter((x) => x.id !== o.id);
			pushToast(tr('rd.overrides.deleted'), 'info');
		} catch {
			/* toast shown */
		}
	}
</script>

<section>
	<h2>{tr('rd.overrides')}</h2>
	{#if items.length === 0}
		<p>{tr('rd.overrides.none')}</p>
	{:else}
		<ul>
			{#each items as o (o.id)}
				<li>
					<span lang="ja">{o.matchText}</span>
					→ <span lang="ja">{o.tokens.map((t) => t.surface).join(' | ')}</span>
					({tr(`rd.overrides.scope.${scopeOf(o)}`)})
					<button type="button" onclick={() => remove(o)}>{tr('rd.overrides.delete')}</button>
				</li>
			{/each}
		</ul>
	{/if}
</section>
