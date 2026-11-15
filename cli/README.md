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
`skip`, `refresh`. The five-button names (`nothing`, `something`, `okay`) are mapped to the
user's button mode, so `good` and `again` work in both modes.

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

Template fields: `conn`, `new`, `learning`, `review`, `pending`, `day_ends_at`, `notice`,
`last_rating`, `undoable` (`true`/`false`), `card_id`, `kind`, `front`, `back`, `reading`,
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

Add `"custom/kotobaya"` to `modules-right` (or another list). The text is `new / learning / review`.
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
