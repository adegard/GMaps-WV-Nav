GMaps WV Nav
============

GMaps WV Nav is an improved fork of the [DivestOS GMaps WV](https://github.com/Divested-Mobile/Maps)
WebView wrapper for using Google Maps without exposing your device, with
turn-by-turn navigation and easy place sharing added.

Features
--------
- Clears private data on close
- Blocks access to Google trackers and other third-party resources
- Restricts all network requests to HTTPS
- Allows toggling of location permission
- **Turn-by-turn navigation**:
  - Floating "Navigate" button hands the current place/coordinates to the
    Google Maps app (`google.navigation:`) for real turn-by-turn guidance,
    trying multiple launch strategies (scheme, explicit Maps package, and a
    `dir_action=navigate` URL) until one is handled
  - If no navigation app is installed, you are asked whether to show an in-app
    route preview instead of silently falling back
  - Intercepts `intent://`, `google.navigation:` and `waze:` links fired by the
    maps page itself (e.g. its "Start" button) and forwards them correctly
- **Easy place sharing**:
  - Floating "Share" button shares the current place as a link
    (`https://www.google.com/maps/search/?api=1&query=lat,lng`)
  - Copying a maps link or coordinates (e.g. from the in-page Share button)
    pops up quick actions: Share / Navigate / Copy
  - The app accepts `ACTION_SEND` text/links and `geo:` / maps URLs from other apps

Build
-----
The APK is built automatically by GitHub Actions and uploaded as an artifact:

- On every push to `main` / `master` and via "Run workflow" (`workflow_dispatch`)
- On tag pushes (`v*`) a GitHub Release with the signed release APK is created

To build locally:

    ./gradlew assembleRelease

Downsides
---------
- Turn-by-turn guidance itself runs in the Google Maps app (WebView cannot host
  the live navigation screen); if the Maps app is missing, only route preview
  is available in-app
- WebRTC isn't blocked due to WebView limitations
- Cache isn't cleared due to resource/data considerations, however could allow
  tracking without other data (cookies)
  - Manually clear app cache if necessary

Security note
-------------
The `signing/release.keystore` in this repository is used so that releases built
by the workflow are reproducible and updatable. It is a personal throwaway key;
keep the repository private or replace the key before wide distribution.

Credits
-------
- @woheller69 for discovering that page loaded resources weren't being blocked
- @woheller69 for adding proper location support
- @woheller69 for adding location sharing to other map apps
- @woheller69 for disabling WebView telemetry
- R Raj for the sharing intent support
- Icons: Google/Android/AOSP, License: Apache 2.0, https://google.github.io/material-design-icons/
- Divested Computing Group for the original project

License
-------
GNU AGPL-3.0, see [LICENSE](LICENSE).
