<script lang="ts">
	import Page from '../../../lib/ui/Page.svelte';
	import { tn } from '../../../lib/note-types/messages';
	import { NoteTypeNewPage } from '../../../lib/state/noteTypes.svelte';
	import Editor from '../Editor.svelte';

	const s = new NoteTypeNewPage();
</script>

<svelte:head><title>{tn('nt.new')} · Kotobaya</title></svelte:head>

<Page title={tn('nt.new')} back={{ href: '/note-types', label: tn('nt.title') }}>
	<Editor bind:draft={s.draft} isNew={true} busy={s.busy} submitLabel={tn('nt.create')} onsubmit={() => void s.create()} />
</Page>
