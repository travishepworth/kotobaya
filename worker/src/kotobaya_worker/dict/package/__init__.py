"""Dictionary release packaging for devices (T103).

Turns the server's ``ref.*`` tables into the files a phone downloads, and the diff between two
releases. Nothing here serves them: the manifest and blob endpoints belong to a later server
ticket. The API manifest is ``ref.dict_release.artifacts`` (``DictRelease.artifacts``), which
:func:`build_release` returns ready to store.

Artifacts
---------
``full``  zstd-compressed SQLite file with exactly the ``Dict.sq`` schema (``PRAGMA user_version
          = 1``). Rows are inserted in primary-key order, so equal content gives equal bytes for a
          given SQLite version. Scope ``top60k`` holds the entries with ``freq_rank <= N`` (not
          retired) plus the caller's referenced seqs (retired ones included, flagged
          ``retired = 1``); scope ``all`` holds every entry. ``kanji``, ``freq_list`` and the
          release row are always complete; every other child table holds rows for present
          entries only (``example_sentence`` only the sentences linked to a present entry).
``diff``  zstd-compressed UTF-8 JSON, ``format = "kotobaya-dict-diff"``, ``format_version = 1``::

            {"format": "kotobaya-dict-diff", "format_version": 1,
             "from_release": 2, "to_release": 3, "scope": "top60k",
             "tables": {"<table>": {"columns": [...], "pk": [...],
                                    "upsert": [[row values in column order], ...],
                                    "delete": [[pk values], ...]}, ...},
             "row_counts": {"<table>": <rows after applying>, ...}}

          ``upsert`` is every added or changed row (``INSERT OR REPLACE``; a retired entry is a
          changed ``dict_entry`` row with ``retired = 1``). ``delete`` is every row present in the
          old file and absent from the new one (entries leaving the scope, with their child rows;
          the old ``dict_release`` row). Values are JSON integers, strings or null, exactly as
          stored. Apply deletes in reverse table order, then upserts in table order, in one
          transaction, after checking ``dict_release.id == from_release`` and the scope; verify
          ``row_counts`` afterwards. The blob key is the SHA-256 of the compressed bytes.
          ``format_version`` is bumped on any incompatible change; clients must refuse unknown
          versions and fall back to the ``full`` build.

The diff format is not in ``contracts/``: flagged for the architect.
"""
