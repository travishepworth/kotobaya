import { it } from 'vitest';
import { renderCard } from '../../src/lib/core/index';
import { emptyDraft, previewOptions, sampleFields, toCoreNoteType } from '../../src/lib/note-types/draft';
it('x', () => {
	for (const front of ['<div class="user-q">{{front}}</div>', '{{nope}}']) {
		const d = emptyDraft();
		const k = d.kinds[0];
		if (k) k.front = front;
		console.log(JSON.stringify(renderCard({ noteType: toCoreNoteType(d), kind: 'basic', fields: sampleFields(d), mnemonic: '', side: 'front', options: previewOptions(true, false) })));
	}
});
