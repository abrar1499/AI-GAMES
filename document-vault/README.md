# Document Vault (Android)

A small offline Android app for keeping personal documents (CNIC, Iqama, passport, certificates) as photos or PDFs behind a PIN.

- Documents are encrypted with AES-256-GCM. The data key is wrapped with a PIN-derived key (PBKDF2) and an Android Keystore key.
- Only images and PDFs can be added (30 MB limit each), sorted into fixed personal-document categories.
- Locks when the app leaves the screen; screenshots are blocked; no internet permission; excluded from backups.
- Open (zoomable image / PDF pages), rename, change category, share, save a copy, delete.

Plain Kotlin on the Android framework, no third-party libraries. Min Android 8.0 (API 26).

## Build

Needs the Android SDK (platform 35) and Gradle 8.13+.

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
gradle assembleRelease
```

Release signing reads `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) next to this file. It and the keystore are not committed; without them the release APK is unsigned.

An update must be signed with the same key as the installed app. Otherwise Android requires uninstalling first, which deletes the vault, so save copies of the documents before doing that.
