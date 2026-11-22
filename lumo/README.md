# Lumo

A simple, premium Android app for **messages, voice calls and video calls**. You log in with a username and passcode. Nothing else is in the app.

- **Login:** pick a username and passcode once. Use the same ones to log in again. No SMS, so it's free.
- **Chats:** live messages with sent and pending ticks. Find people by their username.
- **Video calls:** Full HD (1080p at 30fps) when the camera supports it, with hardware encoding, an encoder bitrate cap of up to 4.5 Mbps, picture-in-picture, a camera flip button and a preview of yourself that you can drag
- **Voice calls:** Opus audio at 48 kHz and up to 128 kbps, with in-band error correction for lost packets, echo cancellation, noise suppression and the earpiece or proximity screen
- **Calls stay up when you switch networks** (Wi-Fi to mobile data): the call reconnects by itself
- **Calls are encrypted end to end** (WebRTC DTLS-SRTP) and go straight from phone to phone. No server sees the audio or video.
- **Display:** a dark theme drawn edge to edge, running at the screen's highest refresh rate (90, 120 or 144 Hz)

Built with Kotlin, Jetpack Compose, Firebase (Auth, Firestore, Cloud Messaging) and Google's WebRTC engine.

---

## Make it work (about 15 minutes, one time)

Lumo needs your own free Firebase project. Firebase handles login and carries the messages.

### 1. Create the Firebase project
1. Go to <https://console.firebase.google.com> and click **Add project**.
2. Click the **Android** icon to add an app. Use the package name **`com.lumo.app`**.
3. When it asks for SHA certificates, go to **Project settings → Your apps → Add fingerprint** and add both:
   ```
   SHA-1:   8A:D1:4C:E5:FA:0E:93:BE:EC:09:6E:F3:54:AF:DE:92:48:A4:8D:F7
   SHA-256: FD:38:C1:BF:19:3D:B7:BC:3E:6D:E5:10:BA:D0:CB:7A:16:19:83:23:BE:25:CB:9E:D6:C5:C1:12:4A:4F:4C:AB
   ```
   (These belong to `app/lumo.keystore`, the key every build is signed with. They are only needed for push notifications and future Google features.)
4. Download **`google-services.json`** and put it in `lumo/app/`.

### 2. Turn on the services
- **Authentication → Sign-in method → Email/Password → Enable.** (Usernames are stored as `username@lumo.app` accounts.)
- **Firestore Database → Create database** (production mode). Then open the **Rules** tab and paste in [`firebase/firestore.rules`](firebase/firestore.rules).

### 3. Build the APK
**Option A: GitHub builds it for you**
1. Commit `lumo/app/google-services.json`, *or* add its contents as a repository secret named `LUMO_GOOGLE_SERVICES_JSON` (Settings → Secrets and variables → Actions).
2. Open the **Actions** tab, select **Build Lumo APK**, then **Run workflow**.
3. Download the **lumo-apk** artifact. Install `app-arm64-v8a-release.apk` (most phones) or `app-universal-release.apk` (any phone).

**Option B: on your computer** (Android Studio installed)
```bash
cd lumo
./gradlew assembleRelease
# APKs: app/build/outputs/apk/release/
```

### 4. Ring phones even when the app is closed (recommended)
While Lumo is open or recently used, calls ring right away. To make a phone ring when Lumo is fully closed, deploy the small push function. It needs Firebase's pay-as-you-go **Blaze** plan, which has a free tier that covers normal personal use:
```bash
npm install -g firebase-tools
cd lumo/firebase/functions && npm install && cd ..
firebase login
firebase use --add            # pick your project
firebase deploy --only functions,firestore:rules
```
This also sends message notifications.

### 5. Calls between strict mobile networks (optional)
Most calls connect directly. Some mobile carriers block direct connections, and those calls need a **TURN relay**. You can run your own (coturn) or use a hosted one (for example Metered or Twilio), then build with:
```bash
./gradlew assembleRelease -Plumo.turnUrls="turn:your.server:3478,turns:your.server:5349" \
  -Plumo.turnUser="user" -Plumo.turnPass="pass"
```
For GitHub builds, set the secrets `LUMO_TURN_URLS`, `LUMO_TURN_USER` and `LUMO_TURN_PASS` instead.

---

## How it works

| Part | File |
|---|---|
| Username and passcode login | `ui/AuthScreens.kt` |
| Chat list, new chat (search by username) | `ui/HomeScreens.kt` |
| Conversation | `ui/ChatScreen.kt` |
| Call engine: WebRTC, quality tuning, signaling, reconnect | `call/CallManager.kt` |
| Call screen: video, controls, incoming call, picture-in-picture | `call/CallActivity.kt` |
| Audio routing: speaker, earpiece, headset, proximity | `call/AudioRouter.kt` |
| Firestore access | `data/Repo.kt` |
| Push function | `firebase/functions/index.js` |

Firestore layout: `users/{uid}`, `chats/{a_b}/messages/{id}`, `calls/{id}` with `callerIce` and `calleeIce` sub-collections for connection candidates.

**Before you publish on the Play Store,** sign the app with your own private key, not the shared `lumo.keystore`, and add that key's fingerprints to Firebase.
