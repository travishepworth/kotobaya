"""Dictionary release packaging for devices (T103).

Turns the server's ``ref.*`` tables into the files a phone downloads, and the diff between two
releases. Nothing here serves them: the manifest and blob endpoints belong to a later server
ticket. The API manifest is ``ref.dict_release.artifacts`` (``DictRelease.artifacts``), which
:func:`build_release` returns ready to store.

Formats, hashing, storage and the diff-base rule are defined by ``contracts/db/DICT_PACKAGE.md``
(authoritative; this package implements it). Entry points: :func:`release.build_release`
(Postgres to artifacts; the diff base is the *published* ``full`` of the previous release,
downloaded and hash-checked, never rebuilt from ``ref.*``), :func:`release.register_blobs`
(``app.blob`` rows) and :func:`sqlite.apply_diff` (reference implementation of the 3.4 apply
procedure, used by tests).
"""
