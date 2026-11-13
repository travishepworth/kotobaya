<script lang="ts">
	import { tn } from '../../lib/note-types/messages';
	import { NoteTypesListPage } from '../../lib/state/noteTypes.svelte';

	const s = new NoteTypesListPage();

	$effect(() => {
		s.start();
	});
</script>

<h1>{tn('nt.title')}</h1>
<p><a class="kb-btn" href="/note-types/new">{tn('nt.new')}</a></p>
{#if !s.loaded}
	<p>{tn('nt.loading')}</p>
{:else if s.items.length === 0}
	<p>{tn('nt.none')}</p>
{:else}
	<ul>
		{#each s.items as nt (nt.id)}
			<li>
				<a href={`/note-types/${nt.id}`}>{nt.name}</a>
				<small>
					{tn(`nt.base.${nt.baseType}`)} · {tn('nt.fieldCount', { n: nt.fields.length })} ·
					{tn('nt.kindCount', { n: Object.keys(nt.templates).length })}
					{#if nt.builtin}· {tn('nt.builtin')}{/if}
				</small>
			</li>
		{/each}
	</ul>
{/if}

<style>
	small {
		color: var(--kb-text-muted);
	}
	li {
		margin-bottom: var(--kb-space-2);
	}
</style>
