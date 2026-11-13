# W-6 HANDOFF — DONE-partial
`make check MODULE=server` exit=0.

Done:
- OpApplier: grammar.created/updated (winning detection_rules)/deleted (live point with rules) enqueue `grammar-detect:all` (ON CONFLICT DO NOTHING + pg_notify job.updated).
- MediaPublishRunner: parent `grammar-detect` keeps media parse_status/parse_error; non-null create/jpdb is invalid_payload.
- listGrammarExamples, putGrammarSpan, removeGrammarSpan (idempotent; no op if live/absent) wired; allowlist trimmed.
- Tests: GrammarDetectApiTest, runner test.
- Out-of-path edits (needed): JobRepo.API_JOB_KINDS += grammar-detect (InternalJobsTest requires it); NtfySink skips grammar-detect (C-13).

CONTRACT_CHANGE_REQUEST: worker-parse.yaml lacks `POST /internal/grammar/rules/test {rules:[str], sentences:[str]}` -> `{matches:[{rule_index,sentence_index,start_offset,end_offset,text}], errors:[{rule_index,message,column}]}`. `testGrammarRules` stays 501 (still in WiringAuditTest allowlist) until added.

Note: rule parse validation (422) on create/update is still absent.
