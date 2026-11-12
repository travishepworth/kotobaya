<script lang="ts">
	import { t } from '../lib/i18n';
	import { session } from '../lib/session.svelte';
</script>

<svelte:head><title>{t('app.name')}</title></svelte:head>

<h1>{t('app.name')}</h1>
<p>{t('home.welcome')}</p>
{#if session.principal}<p>{session.principal.name}</p>{/if}
