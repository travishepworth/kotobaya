<script lang="ts">
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { ResponseError } from '../../../lib/api/client';
	import { noteTypesApi } from '../../../lib/note-types/api';
	import { fieldsOf, templatesOf, toDraft, type NoteTypeDraft } from '../../../lib/note-types/draft';
	import Editor from '../../../lib/note-types/Editor.svelte';
	import { tn } from '../../../lib/note-types/messages';
	import { pushToast } from '../../../lib/toasts.svelte';

	const id = $derived(page.params.id ?? '');

	let draft = $state<NoteTypeDraft | null>(null);
	let builtin = $state(false);
	let missing = $state(false);
	let busy = $state(false);

	async function load(noteTypeId: string): Promise<void> {
		try {
			const nt = await noteTypesApi.getNoteType({ noteTypeId });
			draft = toDraft(nt);
			builtin = nt.builtin;
		} catch (e) {
			if (e instanceof ResponseError && (e.response.status === 404 || e.response.status === 410)) {
				missing = true;
			}
		}
	}

	$effect(() => {
		if (id) void load(id);
	});

	async function save(): Promise<void> {
		if (!draft) return;
		busy = true;
		try {
			const nt = await noteTypesApi.updateNoteType({
				noteTypeId: id,
				noteTypePatch: {
					name: draft.name.trim(),
					fields: fieldsOf(draft),
					templates: templatesOf(draft),
					css: draft.css
				}
			});
			draft = toDraft(nt);
			pushToast(tn('nt.saved'), 'info');
		} catch {
			/* toast shown */
		} finally {
			busy = false;
		}
	}

	async function remove(): Promise<void> {
		if (!draft || !confirm(tn('nt.confirmDelete', { name: draft.name }))) return;
		try {
			await noteTypesApi.deleteNoteType({ noteTypeId: id });
			pushToast(tn('nt.deleted'), 'info');
			await goto('/note-types', { replaceState: true });
		} catch {
			/* toast shown (built-in: 409) */
		}
	}
</script>

<p><a href="/note-types">{tn('nt.back')}</a></p>
{#if missing}
	<p>{tn('nt.notFound')}</p>
{:else if draft}
	<h1>
		{draft.name}
		{#if builtin}<small>({tn('nt.builtin')})</small>{/if}
	</h1>
	<Editor
		bind:draft
		isNew={false}
		{busy}
		submitLabel={tn('nt.save')}
		onsubmit={save}
		ondelete={builtin ? undefined : remove}
	/>
{:else}
	<p>{tn('nt.loading')}</p>
{/if}
