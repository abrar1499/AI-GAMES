const C='pp-v2';self.addEventListener('install',e=>e.waitUntil(caches.open(C).then(c=>c.addAll(['./','index.html','manifest.json','icon.svg','lib/vision_bundle.mjs','lib/vision_wasm_internal.js','lib/vision_wasm_internal.wasm','lib/selfie.tflite']))));
self.addEventListener('fetch',e=>e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request))));
