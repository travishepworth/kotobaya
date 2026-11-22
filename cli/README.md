# kotobaya CLI

API recipes (curl + CLI): ../docs/API-GUIDE.md

`kotobaya` is the command-line client for the Kotobaya API. It has one-shot commands
(`health`, `cards find`, `media import`, `jobs`) and a review daemon with a widget stream
(`daemon`, `watch`).

## Configuration

The one-shot commands read `KOTOBAYA_URL` and `KOTOBAYA_TOKEN` from the environment.
The daemon also reads `~/.config/kotobaya/config.toml`. Environment variables override the file.
systemd and waybar do not inherit your shell environment, so put the settings in the file:

```toml
url = "https://kotobaya.example.net"   # server origin
token_file = "~/.config/kotobaya/token" # or: token_cmd = "pass show kotobaya/pat"
```

Use one `token_file` or one `token_cmd`, not both.

Optional: `keep_going_idle_s = 600` (or `KOTOBAYA_KEEP_GOING_IDLE_S`, which wins) is how many
seconds without an answer, undo, skip or keep-going press end keep going (ADR-039, default 600 =
10 minutes). It must be a positive number.

Create a personal access token with scopes **read** and **review**. A token without `review`
cannot answer cards: the daemon reports `conn: "auth"` on each answer, so widgets flap between
`ok` and `auth`.

## Review daemon

```sh
install -Dm644 cli/systemd/kotobaya.service ~/.config/systemd/user/kotobaya.service
systemctl --user daemon-reload
systemctl --user enable --now kotobaya
journalctl --user -u kotobaya -f
```

The unit runs `%h/.local/bin/kotobaya daemon` with `Restart=always`. If your install lives
elsewhere, edit `ExecStart`. The daemon listens on `$XDG_RUNTIME_DIR/kotobaya/in` (a FIFO for
keybindings) and `$XDG_RUNTIME_DIR/kotobaya/sock` (a unix socket).

Send actions by writing one word per line to the FIFO. `1<>` opens it read-write, so the write
never blocks, and the keypress is dropped if the daemon is down:

```sh
echo reveal 1<>$XDG_RUNTIME_DIR/kotobaya/in
echo good   1<>$XDG_RUNTIME_DIR/kotobaya/in
```

Actions: `reveal`, `again`, `hard`, `good`, `easy`, `nothing`, `something`, `okay`, `undo`,
`skip`, `refresh`, `keep_going`, `stop_keep_going`. The five-button names (`nothing`, `something`, `okay`) are mapped to the
user's button mode, so `good` and `again` work in both modes. On the socket the same names are
`{"cmd": "..."}`.

**Review loop (ADR-037).** The daemon renders what the server's `loop` says and decides nothing
itself. The state carries `phase` (`reviewing`, `goal_reached`, `done_for_today`, `waiting`,
`empty`), `ready` (cards answerable now: learning due + reviews; new cards and learning cards due
later are not in it), `later_today` (+ `later_next_due_at`), `backlog`, `backlog_beyond_limit`,
`reviews_today`, `reviews_limit`, `new_today`, `new_goal`. `counts`, `more_available` and
`can_keep_going` stay but are deprecated (`can_keep_going` = phase is `goal_reached` or
`done_for_today`, nothing shown, keep going off). `next_due_at` is set only in phase `waiting`
(the daemon's single timer). A server without `loop` gives `"conn": "server_too_old"` (no cards,
`counts` null); the widget says "server too old". A card that came due is always the next card:
once the earliest expected due instant has passed, the cached queue is not served and the daemon
fetches again first.

**Keep going** (per device, not synced). In phase `goal_reached`/`done_for_today` send
`keep_going` to continue past every limit: due reviews first (no cap), then new cards without
end. ADR-039: keep going lasts one review session. It is held in memory only (nothing is written
to disk) and ends on a daemon restart, a different study day, `stop_keep_going`, or when no answer,
undo, skip or keep-going press came for `keep_going_idle_s` (default 600 s). The idle check runs
when a command arrives or the queue is fetched, not on a timer, so the state can show keep going
until the next command or fetch. The idle clock starts at the keep-going press.
The state carries `"keep_going"` (bool); `"loading"` (bool) is true while a refetch runs.

### Keep going from `kby`

```sh
kby -k          # or: kby keep    start keep going (alias: kby do keep_going)
kby -K          # or: kby unkeep  back to today's limits (alias: kby do stop_keep_going)
```

Offered in phases `goal_reached` and `done_for_today` (`"can_keep_going": true`). A learning
card that falls due is still served at its due instant, never early. `kby -f` shows it in the tooltip and the default widget
binds left click to it in the done state; `kby serve` has `actions.keep_going` and
`actions.stop_keep_going`. The daemon state also has `"keep_going"` (active).

## watch

```sh
kotobaya watch                       # json: one daemon state per line (default)
kotobaya watch --format waybar       # waybar custom module JSON
kotobaya watch --format '{conn} {new}/{learning}/{review} {front}'
```

Every line is the full state (`cli/schema/state.v1.json`). Widgets are stateless. While the
daemon is down, `watch` prints the last known state with `conn` set to `offline`, once per outage,
and retries with backoff (0.5 s doubling to 10 s). `watch` needs no token and no config file, only
the daemon's socket.

Template fields: `conn`, `new`, `learning`, `later`, `review`, `pending`, `day_ends_at`, `notice`,
`last_rating`, `undoable` (`true`/`false`), `card_id`, `kind`, `kind_label`, `card_state` (`new`, `learning`,
`review`, `relearning`; empty from an older daemon), `is_new` (`true`/`false`), `front`, `back`, `reading`,
`sentence`, `revealed` (`true`/`false`), `preview_again`, `preview_hard`, `preview_good`,
`preview_easy`. Card text is empty until the server sends it. An unknown field is rejected at
startup.

## Widgets

### waybar

`~/.config/waybar/config.jsonc`:

```jsonc
"custom/kotobaya": {
  "exec": "kotobaya watch --format waybar",
  "return-type": "json",
  "on-click": "echo reveal 1<>$XDG_RUNTIME_DIR/kotobaya/in",
  "on-click-right": "echo undo 1<>$XDG_RUNTIME_DIR/kotobaya/in"
}
```

Add `"custom/kotobaya"` to `modules-right` (or another list). The text is `N learning · M review`, with `N new` before and `K later` between them only when above 0
(ADR-049, the web header's four parts). Numbers come from `until_stop_parts`; an older daemon falls
back to the deprecated `counts`, where `learning` includes the cards due later.
The `class` is `ok`, `offline` or `auth`, for CSS styling.

### eww

`~/.config/eww/eww.yuck`:

```lisp
(deflisten kotobaya :initial "{}" "kotobaya watch --format json")

(defwidget kotobaya-widget []
  (box :class "kotobaya ${kotobaya.conn}" :spacing 6
    (label :text "${kotobaya.counts.new} / ${kotobaya.counts.learning} / ${kotobaya.counts.review}")
    (button :onclick "echo reveal 1<>$XDG_RUNTIME_DIR/kotobaya/in" "show")
    (button :onclick "echo good 1<>$XDG_RUNTIME_DIR/kotobaya/in" "good")))
```

Put `(kotobaya-widget)` in a window definition as usual.

### Hyprland keybindings

`~/.config/hypr/hyprland.conf`. Hyprland runs `exec` through `sh`, so the redirection works:

```conf
bind = $mainMod, R, exec, echo reveal 1<>$XDG_RUNTIME_DIR/kotobaya/in
bind = $mainMod, 1, exec, echo again 1<>$XDG_RUNTIME_DIR/kotobaya/in
bind = $mainMod, 2, exec, echo hard 1<>$XDG_RUNTIME_DIR/kotobaya/in
bind = $mainMod, 3, exec, echo good 1<>$XDG_RUNTIME_DIR/kotobaya/in
bind = $mainMod, 4, exec, echo easy 1<>$XDG_RUNTIME_DIR/kotobaya/in
bind = $mainMod, U, exec, echo undo 1<>$XDG_RUNTIME_DIR/kotobaya/in
bind = $mainMod, S, exec, echo skip 1<>$XDG_RUNTIME_DIR/kotobaya/in
```

Use a modifier, not a bare number key, so the bindings do not fire while you type elsewhere.

## kby: machine interface for GUI widgets

`bin/kby` is a bash wrapper (needs `jq`; install it on your PATH yourself). Besides the shell
actions (`kby -1..-4 -r -u -s -R -n`), `kby -f` and `kby click`, it has two commands for widgets
that draw their own buttons. Both ignore widget confs.

The daemon refuses grades on a hidden card, and publishes `back`, `reading` and `sentence` only
after reveal (`front`, `previews` and `id`/`kind` are always there).

`kby serve` prints one compact JSON line per daemon state change, flushed at once, and never
exits on errors (`offline`, `auth` and `error` are lines; it retries with 1 s doubling to 10 s).
The first line is `loading`. Each line is also written to `$XDG_RUNTIME_DIR/kotobaya/state.json`
(temp file + rename). Use either `serve` or `-f`, not both: they share that file. SIGTERM stops
it and the `kotobaya watch` child.

```json
{"v":1,"state":"front|back|waiting|done|loading|offline|auth|error","rev":"<card_id>:<n>",
 "text":"猫 12","counts":{"new":3,"learning":2,"review":7},
 "card":null,
 "last":{"rating":null,"undoable":false},"notice":null,"error":null,
 "keep_going":false,"more_available":false,
 "actions":{"reveal":{"enabled":true},"again":{"enabled":false,"hint":"1m"},
            "hard":{...},"good":{...},"easy":{...},"undo":{"enabled":false},
            "skip":{"enabled":true},"refresh":{"enabled":true},"next":{"enabled":true},
            "keep_going":{"enabled":false}}}
```

- `card` is `null` or `{"id","kind","front"}`, plus `kind_label` and `state` (`new`, `learning`,
  `review`, `relearning`) when the daemon sends them, plus `reading`, `sentence`, `back` only when
  revealed (absent, never empty, when the server has none).
- `rev` changes on every line (`-` stands for "no card"); send it back with each action.
- `notice` appears on one line only. `hint` (next interval per grade) only when known.
- Enabled: `reveal` on front; grades on back; `skip`/`next` on front and back (`next` = reveal if
  hidden, else good); `undo` only when `last.undoable`; `refresh` always; `keep_going` when `done` or `waiting`
  and `can_keep_going`; `stop_keep_going` while `keep_going`.
- `keep_going` and `more_available` are the daemon's (see "Keep going" above): `done` with
  `more_available` means "done for now, keep going has more"; `done` while `keep_going` means
  nothing is left today.
- `waiting` (ADR-033): nothing due now but a learning card falls due today; `next_due_at` (UTC) and `next_due_in` (m:ss, fixed when the line was written) are set, the daemon refetches at that instant (+1 s), and no card is ever shown early. Counts are the server's, passed through unchanged.

`kby do ACTION [--rev REV]` sends one action (`reveal again hard good easy undo skip refresh
next keep_going`) to the daemon FIFO and returns at once (no reply; the result is the next `serve` line).
Unknown or not-enabled actions print to stderr and exit 2. With `--rev`, if REV is not the
current rev (read from `state.json`; with no `serve` running, compared by card id against the
daemon's status) the action is dropped, exit 0, and the next `serve` line carries the notice
`Ignored: screen changed`.
