import { NoteTypesApi } from '../api/generated';
import { apiConfig } from '../api/client';

export const noteTypesApi = new NoteTypesApi(apiConfig);
