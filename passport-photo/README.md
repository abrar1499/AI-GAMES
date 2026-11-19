# Passport Photo Maker (Web + Android)

Camera/gallery se photo lo, AI se background hatao, suit/blazer/shirt/kurta pehnao, crop/zoom/rotate karo, size chuno (India, US, UK, Canada, visa…), aur 300 DPI single photo ya 4×6 / 5×7 / A4 print sheet download karo. Sab kuch phone/browser me hi hota hai: koi photo kahin upload nahi hoti.

## Web
`www/` folder kisi bhi static host (GitHub Pages, Netlify) par daal do. HTTPS par yeh PWA hai: Android Chrome me "Add to Home screen" se app ki tarah install hota hai.
Local: `npm run serve`

## Android APK (Capacitor)
Zaroori: Node 18+, Android Studio (JDK 17 + SDK).
```
npm install
npm run android:add      # pehli baar
npm run android:build    # APK: android/app/build/outputs/apk/debug/app-debug.apk
```
Ya `npm run android:open` karke Android Studio se build/run karo.
