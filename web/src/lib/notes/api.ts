import { DictionaryApi, NoteTypesApi, NotesApi } from '../api/generated';
import { apiConfig } from '../api/client';

export const notesApi = new NotesApi(apiConfig);
export const noteTypesApi = new NoteTypesApi(apiConfig);
export const dictionaryApi = new DictionaryApi(apiConfig);
