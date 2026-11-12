<script lang="ts">
	import type { Meta } from '../api/generated';
	import { metaApi } from './api';
	import { ts } from './messages';

	let meta = $state<Meta | null>(null);

	$effect(() => {
		metaApi
			.getMeta()
			.then((m) => (meta = m))
			.catch(() => {
				/* toast shown */
			});
	});
</script>

<section>
	<h2>{ts('status.title')}</h2>
	{#if meta}
		<dl>
			<dt>{ts('status.serverVersion')}</dt>
			<dd>{meta.serverVersion}</dd>
			<dt>{ts('status.dictRelease')}</dt>
			<dd>{meta.dictRelease ? meta.dictRelease.version : ts('status.noDict')}</dd>
			<dt>{ts('status.highWater')}</dt>
			<dd>{meta.highWaterSeq}</dd>
			<dt>{ts('status.serverTime')}</dt>
			<dd>{meta.serverTime.toLocaleString()}</dd>
		</dl>
	{/if}
	<p><small>{ts('status.backupsUnknown')}</small></p>
</section>
