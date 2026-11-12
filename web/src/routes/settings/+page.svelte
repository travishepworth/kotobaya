<script lang="ts">
	import { ts } from '../../lib/settings/messages';
	import Devices from '../../lib/settings/Devices.svelte';
	import Presets from '../../lib/settings/Presets.svelte';
	import ServerStatus from '../../lib/settings/ServerStatus.svelte';
	import StudySettings from '../../lib/settings/StudySettings.svelte';
	import Tokens from '../../lib/settings/Tokens.svelte';
</script>

<h1>{ts('settings.title')}</h1>
<StudySettings />
<Presets />
<Devices />
<Tokens />
<ServerStatus />
<p><a href="/settings/attribution">{ts('settings.attribution')}</a></p>
