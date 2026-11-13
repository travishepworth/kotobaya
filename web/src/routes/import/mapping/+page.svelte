<script lang="ts">
	import { onMount } from 'svelte';
	import Button from '../../../lib/ui/Button.svelte';
	import EmptyState from '../../../lib/ui/EmptyState.svelte';
	import Page from '../../../lib/ui/Page.svelte';
	import Panel from '../../../lib/ui/Panel.svelte';
	import Skeleton from '../../../lib/ui/Skeleton.svelte';
	import { ut } from '../../../lib/ui/messages';
	import { jobStatusLabel, MappingPage } from '../../../lib/state/import.svelte';
	import MappingRow from './MappingRow.svelte';
	import { tm } from './messages';

	const vm = new MappingPage();
	onMount(() => void vm.load(false));
</script>

<svelte:head><title>{tm('map.title')} · Kotobaya</title></svelte:head>

<Page title={tm('map.title')} lead={tm('map.intro')} back={{ href: '/import', label: tm('map.back') }} width="measure">
	{#if vm.republish.length > 0}
		<div class="jobs">
			<Panel title={tm('map.republishHeading')}>
				<ul>
					{#each vm.republish as r (r.job.id)}
						<li lang="ja">{tm('map.republish', { title: r.title, status: jobStatusLabel(r.job.status) })}</li>
					{/each}
				</ul>
			</Panel>
		</div>
	{/if}

	{#if !vm.loaded}
		<Skeleton shape="block" height="12rem" />
	{:else if vm.items.length === 0 && vm.cursor === null}
		<EmptyState size="page" art="success" title={tm('map.empty')} body={tm('map.emptyBody')} />
	{:else}
		<ul class="items">
			{#each vm.items as v (v.jpdbVid)}
				<MappingRow vocab={v} onResolved={(res) => vm.resolved(res)} />
			{/each}
		</ul>
		{#if vm.cursor !== null}
			<div class="more"><Button variant="quiet" loading={vm.loading} onclick={() => void vm.load(true)}>{ut('app.loadMore')}</Button></div>
		{/if}
	{/if}
</Page>

<style>
	.jobs {
		margin-bottom: var(--kb-space-5);
	}
	.jobs ul {
		margin: 0;
		padding-left: var(--kb-space-5);
	}
	.items {
		container-type: inline-size;
		display: flex;
		flex-direction: column;
		gap: var(--kb-space-3);
		margin: 0;
		padding: 0;
		list-style: none;
	}
	.more {
		display: flex;
		justify-content: center;
		margin-top: var(--kb-space-3);
	}
</style>
