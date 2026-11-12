import { CardsApi, NotesApi, NoteTypesApi, PresetsApi, ReviewApi, SettingsApi, WordsApi } from '../api/generated';
import { apiConfig } from '../api/client';

export const reviewApi = new ReviewApi(apiConfig);
export const cardsApi = new CardsApi(apiConfig);
export const notesApi = new NotesApi(apiConfig);
export const wordsApi = new WordsApi(apiConfig);
export const presetsApi = new PresetsApi(apiConfig);
export const noteTypesApi = new NoteTypesApi(apiConfig);
export const settingsApi = new SettingsApi(apiConfig);
