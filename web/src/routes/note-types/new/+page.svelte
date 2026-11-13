<script lang="ts">
	import Editor from '../../../lib/note-types/Editor.svelte';
	import { tn } from '../../../lib/note-types/messages';
	import { NoteTypeNewPage } from '../../../lib/state/noteTypes.svelte';

	const s = new NoteTypeNewPage();
</script>

<p><a href="/note-types">{tn('nt.back')}</a></p>
<h1>{tn('nt.new')}</h1>
<Editor bind:draft={s.draft} isNew={true} busy={s.busy} submitLabel={tn('nt.create')} onsubmit={() => void s.create()} />
