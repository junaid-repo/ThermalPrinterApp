# Preprod Android testing

The project has two installable app variants that use the same Android source code.

| Variant | Application ID | App name | Website |
| --- | --- | --- | --- |
| `preprod` | `info.clearbills.app.preprod` | Instabill | `https://preprod.instabill.in/` |
| `release` | `info.clearbills.app` | ClearBills | `https://clearbills.info/` |

Because the application IDs differ, the preprod and production apps can be installed on the same phone. The preprod APK is debug-signed and should be distributed only as an internal test build; it is not restricted to a particular physical phone if someone else obtains the APK.

## Build and install preprod

In Android Studio, open **Build Variants** and select `preprod` for the `app` module, then run the app on the test phone.

From the command line:

```bash
./gradlew :app:assemblePreprod
```

The APK is generated at:

```text
app/build/outputs/apk/preprod/app-preprod.apk
```

With a phone connected and USB debugging enabled, build and install in one step:

```bash
./gradlew :app:installPreprod
```

## Feature-to-production workflow

1. Create a feature branch from `packageUpdate` and make Android changes under `app/src/main`. Code in `main` is shared by preprod and production.
2. Build/run the `preprod` variant and test the Android change against the preprod website.
3. Verify the affected native features, including printing, Bluetooth, camera/file upload, contacts, biometrics, sharing, scanner, Google sign-in, and notifications where relevant.
4. After approval, merge the feature branch into `packageUpdate`.
5. Bump `versionCode` and `versionName` when preparing a store update.
6. In Android Studio, use **Generate Signed App Bundle or APK**, choose the `release` variant and the existing production signing key. The release variant retains the original `info.clearbills.app` package and production URL.

Never publish the `preprod` variant.

## Firebase and Google sign-in

`app/src/preprod/google-services.json` keeps the test build separate from the production source set. The current file uses the existing Firebase project configuration so that an internal APK can be built.

Before treating push notifications as independently configured, register a new Android app named `info.clearbills.app.preprod` in Firebase, download its `google-services.json`, and replace `app/src/preprod/google-services.json`. Do not replace the production file at `app/google-services.json`.

For native Google sign-in, also register `info.clearbills.app.preprod` with the debug signing certificate's SHA-1 and SHA-256 fingerprints in the Google/Firebase project. Otherwise Google can reject sign-in from the new application ID even though the WebView itself works.
