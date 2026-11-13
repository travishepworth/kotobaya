<script lang="ts">
	import { ServerStatusState } from '../state/settings.svelte';
	import { ts } from './messages';

	const vm = new ServerStatusState();

	$effect(() => {
		vm.load();
	});
</script>

<section>
	<h2>{ts('status.title')}</h2>
	{#if vm.meta}
		<dl>
			<dt>{ts('status.serverVersion')}</dt>
			<dd>{vm.meta.serverVersion}</dd>
			<dt>{ts('status.dictRelease')}</dt>
			<dd>{vm.meta.dictRelease ? vm.meta.dictRelease.version : ts('status.noDict')}</dd>
			<dt>{ts('status.highWater')}</dt>
			<dd>{vm.meta.highWaterSeq}</dd>
			<dt>{ts('status.serverTime')}</dt>
			<dd>{vm.meta.serverTime.toLocaleString()}</dd>
		</dl>
	{/if}
	<p><small>{ts('status.backupsUnknown')}</small></p>
</section>
