# Instabill production release

## Release identity

- Application ID: `info.clearbills.app`
- Version code: `18`
- Version name: `1.0.6`
- App label: `Instabill`
- Production website: `https://clearbills.info/`
- Upload keystore: `/home/junaid/AndroidStudioProjects/clearbills.jks`

Version code `17` is the currently published release. Confirm this once more in **Play Console > Test and release > App bundle explorer** before uploading. Every new artifact must use a version code higher than every version code previously uploaded to any Play track.

## Create the signed App Bundle

The command-line release artifacts are intentionally unsigned because signing credentials are not stored in this repository.

1. Open the project in Android Studio.
2. Select **Build > Generate Signed App Bundle or APK**.
3. Select **Android App Bundle**, then select the `app` module.
4. Select `/home/junaid/AndroidStudioProjects/clearbills.jks`.
5. Enter the existing key alias and passwords locally. Do not add them to Git or send them in chat.
6. Select the `release` build variant and finish the wizard.
7. Verify that the generated bundle is for package `info.clearbills.app`, version code `18`, and version name `1.0.6`.

In **Play Console > Setup > App integrity**, compare the upload-key SHA-1 certificate with the signed bundle. The previously signed bundle in this project uses this certificate:

```text
DF:16:58:00:28:3B:B0:AC:39:18:3A:C4:CF:19:AF:A0:DA:C1:01:DF
```

If the certificate does not match the Play Console upload certificate, do not upload or request a production rollout.

## Test through Google Play

1. Open **Play Console > Test and release > Testing > Internal testing**.
2. Create a new release and upload the signed `.aab`.
3. Add release notes describing the minimal changes.
4. Add the test Google account and roll out to internal testing.
5. On the phone, opt in using the internal-test link and install/update from Google Play.
6. Test login, dashboard loading, notifications, scanning, camera/file upload, contacts, biometrics, sharing, Bluetooth printing, PDF download, and app relaunch.

Testing through the internal track validates the artifact with Google Play's real app-signing and delivery process. Do not install a debug-signed `info.clearbills.app` build before this test because its signature will conflict with the Play version.

## Update the Play Store branding

The Android app label and launcher icon do not automatically change the Play Store listing.

In the Play Console main store listing:

- Change the public app name from `ClearBills` to `Instabill` if the store should show the new brand.
- Upload `app/src/main/ic_launcher-playstore.png` as the 512 x 512 store icon.
- Review the short description, full description, screenshots, website, support email, and privacy-policy page for old `ClearBills` branding.

## Production rollout

After the internal test passes:

1. Promote the tested release to production instead of uploading a different bundle.
2. Review Play Console warnings and required declarations.
3. Start with a staged rollout if available, monitor crashes and Android vitals, then increase to 100%.
4. Keep the signed bundle, keystore, alias, and passwords backed up securely.
