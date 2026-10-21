# ultra launcher

An Android home screen written as an [ultra](https://github.com/codegod100/ultra) app (LPython compiled to WebAssembly, Elm-style). The home starts blank with a clock: long-press it to add apps (the drawer opens in a pick mode) or widgets, and long-press anything on it to move or remove it. Swipe down for the drawer with every app and a search (Enter opens the first match); long-press an app there to put it on the home screen, and swipe up to close it. The panel under the clock has a **Windows** button that opens Recents. The layout is saved in localStorage.

## Install

Get the APK from the [`launcher-latest` release](https://github.com/codegod100/launcher/releases/tag/launcher-latest), which CI rebuilds on every push to `main`. Open it once, press Home, and pick **ultra Home** as the default. Builds are signed with the committed `android/app/debug.keystore`, so each one installs over the last.

The **Windows** button uses an accessibility service (`RecentsService`, which only performs the Recents action). Enable it once under Settings → Accessibility; on Android 13+ a sideloaded APK may first need App info → ⋮ → Allow restricted settings.

## Layout

- `web/` is the UI: `app.py` is the ultra app and `app.wasm` its committed build, with `index.html`, `style.css` and the `ultra.js` / `ui.css` runtime the build copies in. `apps.json` and `widgets.json` are demo data for desktop browsers.
- `android/` is a small native shell (Java, no dependencies) that serves `web/` to a transparent WebView over the wallpaper. It answers `apps.json` from `LauncherApps` and `widgets.json` from the installed widget providers, hands icons to the page through the `window.Android` bridge, and draws real `AppWidgetHostView`s over the slots the page reports.
- `ultra/` is the ultra framework, as a git submodule.
- `tests/e2e.cjs` is a Playwright test of the browser demo.

## Build

```sh
git submodule update --init
pixi run build                          # web/app.py -> web/app.wasm
pixi run install-playwright && pixi run test
cd android && ./gradlew assembleDebug   # needs JDK 17 and the Android SDK
```

`pixi run serve` serves `web/` on http://localhost:8000. In a desktop browser the launcher runs against the demo JSON files, shows a toast instead of launching, and draws widgets as labelled placeholders. Click the bar at the top to open the drawer.

To pick up a newer ultra, update the submodule (`cd ultra && git pull origin main`), rebuild, and commit `ultra` together with the rebuilt `web/` files.
