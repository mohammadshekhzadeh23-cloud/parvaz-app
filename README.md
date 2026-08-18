# VRay — simplified Xray-core client for Android

A minimal Android VPN client built on top of **Xray-core** (via the official
[AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) Go→AAR bindings,
the same engine used by v2rayN / v2rayNG / Nekoray). This project does **not**
reimplement the VMess/VLESS/Trojan protocols — it wraps the real, audited core
in a simpler UI with more exposed knobs than most stock clients.

## Two variants: modern vs. legacy devices

This project now builds **two separate APKs** from one codebase, via Gradle
product flavors:

| | **modern** | **legacy** |
|---|---|---|
| Target devices | Phones from ~2017 onward | Older / budget 32-bit phones |
| Architectures | `arm64-v8a` + `armeabi-v7a` | `armeabi-v7a` only |
| Min Android version | 7.0 (API 24) | 7.0 (API 24) — the Xray-core library itself requires this floor |
| Background stats polling | every 1.5s | every 3s (lighter on weak CPUs/battery) |
| Typical APK size | ~25–28 MB | ~15–17 MB |

Give customers the **modern** build by default; only hand out **legacy** to
someone whose phone is old enough that modern refuses to install (Android
6 or below, or a 32-bit-only chip).

Local build commands:
```
./gradlew assembleModernDebug
./gradlew assembleLegacyDebug
```
Output lands in `app/build/outputs/apk/modern/debug/` and
`app/build/outputs/apk/legacy/debug/` respectively. The GitHub Actions
workflow now builds and uploads both as separate downloadable artifacts.

## Size

The zip is now **~31 MB** (down from ~56 MB), because `libv2ray.aar` was
stripped to only the native libraries real phones use:

- Removed: `x86` and `x86_64` builds of the Go core — these only exist for
  running on an Android **emulator on a PC**, never on an actual phone.
- Kept: `arm64-v8a` (basically all phones sold since ~2017) and
  `armeabi-v7a` (older/budget 32-bit devices), so compatibility is unaffected
  for real users.
- Also removed `libv2ray-sources.jar` (a debug-symbols file not needed to
  build).
- `app/build.gradle.kts` now has an explicit `ndk { abiFilters += ... }` so
  the final built APK also only contains these two architectures.

If you want it even smaller and are fine dropping support for older 32-bit
phones, I can strip `armeabi-v7a` too and go `arm64-v8a`-only — that would
take the AAR down to roughly 18 MB.

## New in this update: smart-connect features

- **Subscription links, grouped**: in Settings, add one or more subscription
  URLs (each with its own label). Each becomes its own group in the server
  list — see "Server groups" below for how refresh/delete work per group.
  If a provider sends a `subscription-userinfo` header (the common
  Shadowsocks/Clash convention: `upload=...; download=...; total=...;
  expire=...`), that group's days-left and data-used/total show as a card on
  the main screen.
- **Quick Connect**: pings every saved server concurrently and connects to
  whichever answers fastest — no manual picking needed.
- **Auto-reconnect**: if the network changes (WiFi ↔ mobile data) or the
  tunnel drops while the app thinks it should be connected, it retries with
  backoff (up to 5 attempts) automatically.
- **Auto-failover**: when enabled, a failed connection attempt tries the next
  saved server instead of just giving up.
- **Kill switch**: when enabled, if the tunnel can't be re-established, the
  VPN interface is kept up (but blackholed) instead of closing — so traffic
  stays blocked rather than silently falling back to the clear network. A
  "رها کردن اتصال" button lets the user manually release it. Note this is a
  software-level switch; for OS-level enforcement point users to Settings →
  VPN → "Block connections without VPN" (a button in Settings opens that
  screen directly via `Settings.ACTION_VPN_SETTINGS`).
- **Onboarding**: a 3-slide intro shown once on first launch
  (`Repository.isOnboardingDone()` / `setOnboardingDone()`).
- **Home-screen widget**: `widget/VpnWidgetProvider.kt` — shows status and
  toggles connect/disconnect for the last-selected server without opening the
  app. If VPN permission hasn't been granted yet it opens the app instead of
  failing silently.

## Release signing

`app/keystore.properties` (gitignored) plus `keystore/parvaz-release.jks`
(also gitignored) are already generated and included in **this zip** so you
can build a signed release APK immediately:

```
./gradlew assembleModernRelease
./gradlew assembleLegacyRelease
```

Output: `app/build/outputs/apk/modern/release/app-modern-release.apk` (and
the `legacy` equivalent) — these are signed, unlike the earlier `debug`
builds, and are what you should actually hand to paying customers.

**Keep `keystore/parvaz-release.jks` and the passwords in
`keystore.properties` private and backed up somewhere safe** (a password
manager, an encrypted drive). If you lose them, you can never publish an
update that Android will treat as "the same app" as an already-installed
one — customers would have to uninstall and reinstall from scratch. Both
files are already in `.gitignore` so a `git push` will never leak them.

**To sign release builds inside GitHub Actions too** (so the workflow also
produces signed APKs, not just debug ones): go to your repo's Settings →
Secrets and variables → Actions, and add four secrets:
- `KEYSTORE_BASE64` — run `base64 -w0 keystore/parvaz-release.jks` locally and
  paste the output
- `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` — copy these straight out
  of `keystore.properties`

Once those secrets exist, every push also builds and uploads
`parvaz-modern-release-apk` and `parvaz-legacy-release-apk` artifacts
alongside the debug ones.

## Server groups (subscription links)

Each subscription link you add in Settings creates its own **group** — the
main server list is now sectioned by where each server came from (a
subscription's label, or "افزوده شده دستی" for anything pasted/scanned by
hand). This means:

- Refreshing one subscription only replaces *that* subscription's servers —
  manually added servers and other subscriptions' servers are untouched.
- Deleting a subscription removes only its own servers.
- You can give customers on different plans different subscription links
  (e.g. "پلن پایه" vs. "پلن VIP"), and each shows as its own labeled section.

## What's included

- **`libv2ray.aar`** already downloaded into `app/libs/` (pulled from the
  project's GitHub release `v26.7.31` — verify you're happy with that version,
  or swap in a newer one from the same repo's Releases page).
- Link import for `vmess://`, `vless://`, `trojan://`, `ss://` share links
  (`data/LinkParser.kt`).
- A JSON config builder (`data/XrayConfigBuilder.kt`) exposing more options
  than a typical simple client:
  - custom SOCKS/HTTP local ports
  - UDP toggle
  - Mux (multiplexing) on/off + concurrency
  - routing mode: global / bypass-Iran / bypass-China (geoip-based)
  - LAN bypass toggle
  - custom DNS servers
  - per-app tunneling: all apps / only selected / all except selected
- `core/ProxyVpnService.kt` — the actual `VpnService` that opens the TUN
  interface and hands its file descriptor straight to Xray-core
  (`CoreController.startLoop(config, tunFd)`).
- A Jetpack Compose UI with:
  - server list, add-by-link dialog **with inline error feedback** on bad links
  - a **per-server edit form** (name, address, port, id/password, network, TLS, SNI, path, host)
  - **connecting/connected/error status** with a colored indicator and spinner
    (backed by `ProxyVpnService`'s `StateFlow<ConnectionState>`)
  - **live traffic stats** (↑/↓ bytes) polled from the core every 1.5s while connected
  - a **latency test button per server** using `Libv2ray.measureOutboundDelay`,
    which works without connecting first
  - a **per-app picker screen** (searchable, system-app toggle) wired to
    `AppSettings.selectedApps` / `PerAppMode`
  - a custom indigo/teal color scheme and typography instead of stock Material3 defaults

## Building — easiest option: let GitHub build it for you (no local install)

This project includes `.github/workflows/build-apk.yml`, which builds the
debug APK in the cloud on GitHub's free runners. You don't need Android
Studio, the Android SDK, or even Gradle installed locally — just Git (VS
Code has this built in) and a free GitHub account.

1. Create a new **empty** repository on github.com (no README/gitignore).
2. In VS Code, open a terminal in this `VPNApp` folder and run:
   ```
   git init
   git add .
   git commit -m "initial"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo>.git
   git push -u origin main
   ```
3. On GitHub, open your repo → the **Actions** tab. The "Build APK" workflow
   starts automatically on push (or click **Run workflow** to trigger it
   manually).
4. Wait for the run to finish (a few minutes) — a green checkmark means it
   succeeded.
5. Click into the finished run → under **Artifacts**, download
   `parvaz-debug-apk` (a zip containing `app-debug.apk`).
6. Send that APK file to your phone (Telegram-to-self, Google Drive, USB —
   anything) and tap it to install. Android will ask you to allow "install
   from unknown sources" the first time — approve it.

This produces a **debug** build, which is fine for testing and for handing
to early customers. For a production/signed release APK later, you (or I)
can extend the workflow to sign it with a release keystore.

## Building locally instead (if you'd rather not use GitHub)

1. Open the `VPNApp/` folder in **Android Studio** (Koala/2024.1 or newer).
2. Let Android Studio generate the Gradle wrapper if prompted (this project
   ships `settings.gradle.kts` / `build.gradle.kts` but not the wrapper jar,
   since I couldn't fetch it from this sandbox — Android Studio will offer to
   add it automatically on first open, or run `gradle wrapper` yourself if
   you have Gradle installed locally).
3. Sync Gradle, then Run on a device or emulator (min SDK 24).
4. On first connect, Android will show the standard "This app wants to set up
   a VPN connection" system dialog — that's `VpnService.prepare()`, required
   for any VPN app, not something this code can bypass.

## Extending it further

- **Per-app picker UI**: `AppSettings.selectedApps` already exists and is
  wired into `ProxyVpnService`, but there's no package-picker screen yet —
  add one using `PackageManager.getInstalledApplications()`.
- **Subscription URLs**: `LinkParser` currently only parses a single pasted
  link. Add an HTTP fetch + base64 decode of a subscription URL, split on
  newlines, and call `LinkParser.parse()` per line.
- **Latency test**: `CoreController.measureDelay(url)` (already in the AAR)
  can back a "ping" button per server.
- **Traffic stats**: `controller.queryStats(tag, direct)` gives you live
  up/down byte counters for a status bar.

## Branding — this build is already set up as "پرواز"

- App name: **پرواز** (`app/src/main/res/values/strings.xml`)
- Icon: a simple paper-airplane vector (`app/src/main/res/drawable/ic_vpn.xml`,
  used for both the launcher icon and the splash screen) — swap in your real
  logo here before giving this to customers.
- Splash screen on launch (via `androidx.core:core-splashscreen`), background
  color in `res/values/colors.xml` (`launcher_bg`).
- UI is fully in Persian, RTL-forced regardless of the device's system
  language (`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`
  in `MainActivity.kt`).
- **QR code import**: the "افزودن سرور" (add server) dialog has a scan button
  using `zxing-android-embedded`, in addition to pasting a link.
- A placeholder support dialog (bell icon in the top bar) currently says
  `@YourSupportID` — replace that string in `MainActivity.kt` with your real
  Telegram/WhatsApp/etc. contact before shipping.

**Before publishing to real customers, also do this yourself** (none of it
is code-complexity, just identity/config, so it's on you to fill in):
- Change `applicationId`/`namespace` in `app/build.gradle.kts` away from
  `com.example.vray` to something like `com.yourbrand.vpn`.
- Replace `ic_vpn.xml` with your actual logo artwork (or hand a proper PNG/SVG
  to Android Studio's Image Asset tool to generate all launcher densities).
- Decide how you'll actually distribute the APK to customers (direct APK
  file, your own site, a Telegram bot, etc.) since this app isn't going
  through Google Play.


- This is a general-purpose proxy/VPN client, the same category of app as
  v2rayN, Nekoray, or Shadowrocket — it has no special or hidden capability
  beyond what those apps do.
- Circumventing network restrictions may be illegal or against terms of
  service in some jurisdictions/networks. That's on you to check locally —
  I'm just handing you working client code.
- I downloaded a real third-party binary (`libv2ray.aar`) from its official
  GitHub release into this project. Review the upstream project yourself
  before trusting it with your traffic, the same way you should for any VPN
  client, closed or open source.
