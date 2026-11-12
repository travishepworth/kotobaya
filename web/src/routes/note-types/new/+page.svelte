<script lang="ts">
	import { goto } from '$app/navigation';
	import { noteTypesApi } from '../../../lib/note-types/api';
	import { emptyDraft, toCreate } from '../../../lib/note-types/draft';
	import Editor from '../../../lib/note-types/Editor.svelte';
	import { tn } from '../../../lib/note-types/messages';
	import { pushToast } from '../../../lib/toasts.svelte';

	let draft = $state(emptyDraft());
	let busy = $state(false);

	async function create(): Promise<void> {
		busy = true;
		try {
			const nt = await noteTypesApi.createNoteType({ noteTypeCreate: toCreate(draft) });
			pushToast(tn('nt.created'), 'info');
			await goto(`/note-types/${nt.id}`, { replaceState: true });
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}
</script>

<p><a href="/note-types">{tn('nt.back')}</a></p>
<h1>{tn('nt.new')}</h1>
<Editor bind:draft isNew={true} {busy} submitLabel={tn('nt.create')} onsubmit={create} />
