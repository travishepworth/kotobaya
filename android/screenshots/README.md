# android/screenshots — Android screenshot matrix

`make screenshots-app` renders every screen of the app (`kotobaya.android.demo.DemoScreens`) over the shared demo
dataset (`fixtures/demo/`) with Roborazzi on Robolectric, and writes

    android/screenshots/<scenario>/<screen>/<device>-<light|dark>-<font100|font200>.png

- devices: `phone` (411x891 dp), `foldable` (673x841 dp, unfolded), `tablet` (1280x800 dp)
- scenarios: `default` (every screen), `empty` (the screens with an empty state)

It is the design feedback loop, not a gate: `make check` skips it, and nothing here is compared. The images are not
committed (this folder is gitignored except this README). The review card is a WebView, which Robolectric does not
draw, so the review shots show the screen around the card. Previews of the same screens: `demo/DemoPreviews.kt`.
