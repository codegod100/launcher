# ultra launcher

An Android home screen written as an [ultra](https://github.com/nandi-conda/ultra) app (LPython compiled to WebAssembly, Elm-style). The home starts blank (add a clock widget if you want one): long-press it to add apps (the drawer opens in a pick mode) or widgets, and long-press anything on it to drag it somewhere else; let go without moving to get its menu, or, for a widget, handles on its edges that resize it to the grid. Swipe down for the drawer with every app and a search (Enter opens the first match); long-press an app there for its menu (add to home, app info, uninstall; system apps have no uninstall), and swipe up to close it. The panel at the top has a **Windows** button that opens Recents. The layout is saved in localStorage.

## Install

Get the APK from the [`launcher-latest` release](https://github.com/codegod100/launcher/releases/tag/launcher-latest), which CI rebuilds on every push to `main`. Open it once, press Home, and pick **ultra Home** as the default. Builds are signed with the committed `android/app/debug.keystore`, so each one installs over the last.

The **Windows** button uses an accessibility service (`RecentsService`, which only performs the Recents action). Enable it once under Settings → Accessibility; on Android 13+ a sideloaded APK may first need App info → ⋮ → Allow restricted settings.

## Layout

- `web/` is the UI: `app.py` is the ultra app and `app.wasm` its committed build, with `index.html`, `style.css` and the `ultra.js` / `ui.css` runtime the build copies in. `apps.json` and `widgets.json` are demo data for desktop browsers.
- `android/` is a small native shell (Java, no dependencies) that serves `web/` to a transparent WebView over the wallpaper. It answers `apps.json` from `LauncherApps` and `widgets.json` from the installed widget providers, hands icons to the page through the `window.Android` bridge, and draws real `AppWidgetHostView`s over the slots the page reports.
- The ultra framework comes from the `ultra` conda package on [prefix.dev/nandi-testing](https://prefix.dev/channels/nandi-testing), which pixi installs.
- `tests/e2e.cjs` is a Playwright test of the browser demo.

## Build

```sh
pixi run build                          # web/app.py -> web/app.wasm
pixi run install-playwright && pixi run test
cd android && ./gradlew assembleDebug   # needs JDK 17 and the Android SDK
```

`pixi run serve` serves `web/` on http://localhost:8000. In a desktop browser the launcher runs against the demo JSON files, shows a toast instead of launching, and draws widgets as labelled placeholders. Click the bar at the top to open the drawer.

Every push to ultra's main publishes a new `ultra` package. To pick it up, run `pixi update ultra`, rebuild, and commit the rebuilt `web/` files.
