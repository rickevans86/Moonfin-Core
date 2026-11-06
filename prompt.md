# Rebuilding Moonfin-Auto from one prompt

This file has two parts:

1. **The full prompt.** Paste it into Claude (with access to a fresh fork of
   [Moonfin-Client/Moonfin-Core](https://github.com/Moonfin-Client/Moonfin-Core))
   to rebuild everything in this fork in one go.
2. **The snippets.** The same prompt broken into self-contained pieces, each
   with what it does, why it's written the way it is, and how to adapt it for
   a different app. Copy just the pieces you need.

The prompt includes the lessons that took several rounds to learn the first
time (install failures, Android permission errors, video sizing), so a rebuild
shouldn't hit them again.

Replace these placeholders throughout:

| Placeholder | Meaning | This fork |
|---|---|---|
| `{OWNER}/{REPO}` | Your fork on GitHub | `rickevans86/Moonfin-Core` |
| `{UPSTREAM}` | The original project | `Moonfin-Client/Moonfin-Core` |
| `{APP_NAME}` | Your app's display name | `Moonfin-Auto` |
| `{APP_ID}` | Your app's Android application ID | `org.moonfin.auto` |
| `{TZ_CRON}` | UTC cron time for the daily sync | `17 20 * * *` (7:17am Melbourne) |

---

## Part 1: The full prompt

````text
I have a fork of a Flutter media app at github.com/{OWNER}/{REPO}, forked from
{UPSTREAM}. Make all changes directly in my fork and push them to main. You
can't build Android apps locally, so all building happens in GitHub Actions;
keep each change small enough to reason about without compiling it, and
explain anything I need to do on my phone or on GitHub in plain steps.

1. IDENTITY
   - Rename the app to "{APP_NAME}": the Android launcher label (the
     baseAppName in android/app/build.gradle.kts) and the in-app "appTitle"
     string in every lib/l10n/app_*.arb plus the committed generated
     lib/l10n/app_localizations_*.dart files.
   - Give the phone ("mobile") flavor its own applicationId, {APP_ID}, so it
     installs alongside the official app instead of trying to replace it.
   - The upstream google-services.json doesn't list the new id, so turn the
     com.google.gms.google-services Gradle plugin off (apply(false)); push
     messaging already skips itself when Firebase isn't configured.
   - Anywhere the code excludes the app's own package by name (e.g. the
     external player list), add the new id too.

2. SIGNING
   Generate a release keystore (PKCS12, RSA 4096, alias "moonfin", 10000-day
   validity, random 24-char password) and give me two files: the keystore and
   a text file listing the five GitHub Actions secrets the existing build
   expects (ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_TYPE,
   ANDROID_STORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD) with
   their values. Tell me where to paste them and to keep both files safe.

3. RELEASES
   Add .github/workflows/release-{app}.yml that runs on every push to main and
   on manual dispatch, with contents: write permission:
   - Java 17, Flutter at the version the existing build.yml pins.
   - Fail with a clear error if the signing secrets are missing (so no
     release is ever signed with a throwaway key), otherwise decode the
     keystore to android/app/release.keystore and write
     android/keystore.properties.
   - Version name = the pubspec version + "." + github.run_number (e.g.
     3.0.0.12); version code = 40000000 + run_number, so it only ever goes up.
   - flutter build apk --release --flavor mobile with those versions and
     --dart-define=DISTRIBUTION_CHANNEL=apk.
   - Publish a GitHub Release tagged v<version> with the APK named
     {APP_NAME}_v<version>.apk, marked latest.
   - Turn off the upstream web Pages deploy's push trigger (manual only).

4. IN-APP UPDATES FROM MY FORK
   - Point the app's update checker (lib/data/services/app_update_service.dart)
     and the About screen's source/issues links at {OWNER}/{REPO}.
   - On Android, when the update asset is an .apk, download it inside the app
     with the existing progress dialog (the Windows updater already does this)
     into cache/updates/, then hand it to the system installer through a
     FileProvider (authority ${applicationId}.updates, cache-path updates/)
     and a platform-channel method "installApk". Add
     REQUEST_INSTALL_PACKAGES to the mobile manifest. If installs from this
     app aren't allowed yet, open ACTION_MANAGE_UNKNOWN_APP_SOURCES for the
     package and tell the user to come back and check again.
   - The update banner sits above the app's Navigator, so its Download button
     must use the router's navigator context for the dialog.

5. STAY IN SYNC WITH THE OFFICIAL APP
   Add .github/workflows/sync-upstream.yml, daily at {TZ_CRON} and on manual
   dispatch (contents: write, actions: write):
   - Read the latest release tag of {UPSTREAM} with gh api.
   - Fetch that tag; if it's already an ancestor of main, stop.
   - Otherwise merge it into main as github-actions[bot]. On conflict, abort,
     list the conflicting files in the run summary, and fail the run.
   - Push using secrets.SYNC_TOKEN if set, else github.token. If the push is
     refused (the built-in token can't push changes to .github/workflows),
     explain in the summary how to create a fine-grained token with Contents
     and Workflows read/write and save it as SYNC_TOKEN.
   - A push made with github.token doesn't trigger other workflows, so in
     that case start the release workflow with gh workflow run.

6. DOWNLOADS
   - Show the Downloads entry in navigation whenever the user is allowed to
     download, not only once something is saved.
   - On Android, default downloads to the shared Download/<app> folder
     (the existing "mediastore" option) instead of private app storage, so
     they survive reinstalls. Keep the setting to switch back.

7. VIDEO ON THE ANDROID AUTO SCREEN
   Google's official parked-video support for Android Auto isn't available to
   sideloaded apps, so use the unofficial projection API that Fermata Auto
   uses (github.com/AndreyPavlenko/Fermata).

   a. Official groundwork (harmless, ready for when Google opens it up): in
      the mobile manifest set android:appCategory="video" and add a
      CAR_LAUNCHER intent filter to MainActivity.

   b. The SDK: copy fermata/lib/auto/aauto.aar from Fermata into
      android/app/libs/ with a README saying where it came from. Add
      implementation(files("libs/aauto.aar")), androidx.fragment and
      androidx.recyclerview. Add ProGuard keep rules for
      com.google.android.apps.auto.sdk.**, com.google.android.gms.car.**,
      android.support.car.** and the app's car package.
      IMPORTANT: the aar has no targetSdk, so the manifest merger silently
      adds READ_PHONE_STATE, and an APK with that permission failed to
      install on a Samsung phone ("App not installed"). Remove it in the
      mobile manifest with tools:node="remove".

   c. Registration: declare a service MoonfinCarService extends
      CarActivityService (exported, label "{APP_NAME} Video") with an intent
      filter for action MAIN and categories
      com.google.android.gms.car.category.CATEGORY_PROJECTION and
      CATEGORY_PROJECTION_OEM. Override res/xml/automotive_app_desc.xml in
      the mobile flavor to declare media, service AND projection; without
      "projection" Android Auto never lists the app.

   d. Drawing on the car: do NOT try to move MainActivity onto a virtual
      display. Android refuses to start an app's activity on an untrusted
      virtual display it created ("Permission Denial ... with
      launchDisplayId"), even with allowEmbedded and SYSTEM_ALERT_WINDOW.
      Instead the app's one Flutter engine (audio_service's cached engine,
      AudioServicePlugin.getFlutterEngine(context)) draws into a FlutterView
      hosted inside MoonfinCarActivity (extends CarActivity):
      - On car resume: detach MainActivity's FlutterView from the engine
        (find it by walking the activity's decor view), create a FlutterView
        in the car activity, attach it, and call
        lifecycleChannel.appIsResumed() because the phone is usually locked
        and Flutter stops drawing while paused.
      - Override MainActivity.shouldDispatchAppLifecycleState() to return
        false while the car owns the engine, so the phone's pause/stop
        doesn't freeze the car screen.
      - When MainActivity resumes on the phone, hand the engine back
        (detach car view, re-attach the phone's) and show "open on your
        phone, tap to show it here" on the car; tapping takes it back.
      - When the car activity is destroyed, end the session the same way.
      - Car back button -> engine.navigationChannel.popRoute().
      - Guard any "discard the headless engine" logic in MainActivity so it
        never destroys the engine while the car is using it.
      - Hide the car app header; setIgnoreConfigChanges(-1).

   e. Sizing: the native video player is a hybrid-composition platform view
      owned by the phone's activity, and Flutter undoes the mutator matrix
      with the PHONE's density. If the car's FlutterView reports the car's
      density, video comes out shrunk by carDensity/phoneDensity. So:
      - Create the car FlutterView with a ContextThemeWrapper whose
        configuration densityDpi is the phone's.
      - Send Dart "uiScale" = carDensity / phoneDensity on a MethodChannel
        ("<package>/car") when attaching, and null when returning to the
        phone.
      - In Dart, wrap the app (where TvUiScale is applied for TV) in a
        CarUiScale widget that, when uiScale is set, lays the app out on
        size / factor with devicePixelRatio * factor inside a FittedBox, the
        same way TvUiScale does. Platform views already undo such scaling
        through UnscaledPlatformView.

   f. Safety: video only while parked. Request ACCESS_FINE_LOCATION
      (prompted from MainActivity.onResume on the phone the first time the
      car screen needs it; the car shows "finish setting up on your phone"
      until then). Watch GPS speed while on the car: >= 3 m/s = driving,
      back to parked after 4 s under 1.1 m/s. While driving, cover the car
      screen with "Video is paused while the car is moving" (blocking
      touches) and send "drivingChanged" true to Dart; send "carClosed" when
      the car screen closes. In Dart (PlaybackLifecycleHandler), pause on
      either if the current item is video, never audio.

   Keep all car code in android/app/src/main/kotlin/<package>/car/
   (CarScreen.kt singleton, MoonfinCarActivity.kt, MoonfinCarService.kt) and
   the Dart side in lib/platform/car_screen_bridge.dart (one handler for the
   channel, listeners + a ValueNotifier<double?> uiScale) and
   lib/util/car_ui_scale.dart.

8. FINISH
   Push each step as its own commit. When done, tell me:
   - How to install: uninstall nothing official; install the APK from my
     Releases page. Android Auto only lists projection apps installed "from
     the Play Store", so install with KingInstaller or
     `adb install -r -i com.android.vending <apk>`.
   - How to enable Android Auto developer mode (tap Version 10 times) and
     turn on Unknown sources, then find "{APP_NAME} Video" under Customise
     Launcher.
   - That if anything fails on the car screen, the message there includes
     the reason, and I should send you a photo.
````

---

## Part 2: The snippets

Each snippet below is one numbered section of the prompt. They're written to
stand alone, so you can paste one into a prompt for a different project after
swapping the placeholders and file paths.

### Snippet 1: Identity (rename + side-by-side install)

**What it does:** gives your fork its own name and Android application ID, so
it installs as a separate app next to the original instead of being rejected
as a mismatched update.

**Why it's written this way:**
- Android refuses to install an APK over an app with the same ID but a
  different signing key ("App not installed"). A new ID avoids that.
- Changing the ID breaks Firebase's `google-services.json` check at build
  time, so the plugin has to be turned off (or you add your own Firebase
  project).
- Flutter apps often commit their generated localization files, so the
  title has to change in both the `.arb` sources and the generated `.dart`.

**Reuse it for:** any fork of an Android app you want to run alongside the
original. Drop the Firebase line if the app doesn't use it.

### Snippet 2: Signing

**What it does:** creates one permanent release key and turns it into GitHub
secrets, so every build is signed the same way and installs as an update.

**Why:** without a fixed key, CI signs each build with a throwaway key, and
Android won't update one build with the next. Losing the key means users have
to uninstall, so the prompt asks for safe storage.

**Reuse it for:** any Android project built in GitHub Actions. Match the
secret names to whatever your workflow reads.

### Snippet 3: Releases

**What it does:** every push to main builds a signed APK and publishes it on
your repo's Releases page.

**Why:**
- The four-part version (`base.run_number`) keeps the upstream version
  visible while making every fork build newer than the last, which the
  in-app updater relies on.
- The version code is `40000000 + run_number`, so it only ever increases and
  sits above the original app's codes.
- Failing on missing secrets prevents a build that can't update the
  previous one.

**Reuse it for:** any Flutter or Android app. Remove `--flavor` if the
project has no flavors; for plain Gradle swap the build step for
`./gradlew assembleRelease`.

### Snippet 4: In-app updates from your fork

**What it does:** the app checks your fork's latest release, downloads the
APK itself, and opens Android's installer. No browser involved.

**Why:**
- `REQUEST_INSTALL_PACKAGES` plus a `FileProvider` is the only supported way
  to hand an APK to the system installer.
- The first time, Android needs "install unknown apps" allowed for your app,
  so the code sends the user to that screen.
- Banners drawn above the Navigator can't open dialogs with their own
  context, hence the navigator-context note.

**Reuse it for:** any sideloaded Android app that should update itself from
GitHub Releases.

### Snippet 5: Stay in sync with the official app

**What it does:** daily, merges any new official release into your fork and
starts a new release build. Conflicts fail loudly with a list of files.

**Why:**
- Syncing to release tags rather than every upstream commit keeps your
  builds on tested versions.
- GitHub's built-in token can't push changes to workflow files, and pushes
  made with it don't start other workflows. The prompt handles both.

**Reuse it for:** any long-lived fork. Change the upstream repo and the
release workflow's file name.

### Snippet 6: Downloads

**What it does:** keeps the Downloads entry visible and saves downloads to a
shared folder.

**Why:** a new app ID can't see the old app's private storage, and an entry
that only appears once something is downloaded looks like a bug. A shared
folder also survives reinstalls.

**Reuse it for:** Moonfin-based apps. For other apps, the general idea is
"store user media in shared storage (MediaStore) rather than app-private
storage".

### Snippet 7: Video on the Android Auto screen

This is the biggest part. Its sub-steps can be reused separately.

**7a. Official groundwork.** `appCategory="video"` and `CAR_LAUNCHER` are
Google's official parked-app hooks. They do nothing for sideloaded apps
today but cost nothing to include.

**7b. The SDK.** Fermata's `aauto.aar` is Google's unofficial Android Auto
SDK. The `READ_PHONE_STATE` removal is the fix for the "App not installed"
failure: old libraries without a targetSdk make the manifest merger add
permissions you never asked for. *Reusable lesson:* when an APK suddenly
won't install, compare its merged permissions with the previous version's.

**7c. Registration.** Android Auto finds projection apps through the
`CATEGORY_PROJECTION` service and only lists them if
`automotive_app_desc.xml` says `projection`. *Reusable for:* any app that
wants a full-screen custom UI on Android Auto.

**7d. Drawing on the car.** This is the core trick: rather than moving an
activity to the car (Android blocks it), point the app's existing Flutter
engine at a view on the car screen. *Reusable for:* any Flutter app that
needs to draw on a second screen it doesn't own as an activity (car
displays, `Presentation` screens). The lifecycle and "hand back to the phone"
rules are what keep it from freezing or crashing.

**7e. Sizing.** Native views embedded in Flutter are positioned with the
density of the context that created them. If the screen Flutter draws on has
a different density, they shrink or grow. *Reusable lesson:* give the Flutter
view the native views' density, then rescale the Dart layout to the real
screen with a FittedBox and an adjusted MediaQuery.

**7f. Safety.** GPS-speed parking detection with hysteresis (fast to lock,
slow to unlock), a touch-blocking cover, and video-only pausing.
*Reusable for:* any in-car feature that should only work while parked.

### Snippet 8: Finish

**What it does:** asks for one commit per step and the exact phone-side
setup steps at the end.

**Why:** Android Auto hides projection apps that weren't installed "from the
Play Store", so the install method (KingInstaller or
`adb install -i com.android.vending`) matters as much as the code. Showing
error reasons on the car screen turned guesswork into one-photo fixes.

---

## Status when this was written

Built up to release 3.0.0.12. The video plays on the Android Auto screen; the
car-density layout (7e) was the last change and still needed a test drive to
confirm menus and the player fit the car screen properly.
