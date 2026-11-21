// Sends a high-priority push so the other phone rings (or shows the message)
// even when Lumo is closed. Deploy with: firebase deploy --only functions
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

async function tokenOf(uid) {
  const snap = await db.doc(`users/${uid}`).get();
  return snap.get("fcm");
}

async function push(token, data, ttlMs) {
  if (!token) return;
  try {
    await admin.messaging().send({ token, data, android: { priority: "high", ttl: ttlMs } });
  } catch (e) {
    console.warn("push failed", e.code || e.message);
  }
}

exports.ringCallee = onDocumentCreated("calls/{callId}", async (event) => {
  const call = event.data.data();
  if (!call || call.status !== "ringing") return;
  await push(await tokenOf(call.callee), { type: "call", callId: event.params.callId }, 45 * 1000);
});

exports.notifyMessage = onDocumentCreated("chats/{chatId}/messages/{messageId}", async (event) => {
  const m = event.data.data();
  if (!m || !m.to) return;
  const sender = await db.doc(`users/${m.sender}`).get();
  await push(await tokenOf(m.to), {
    type: "message",
    chatId: event.params.chatId,
    from: sender.get("name") || sender.get("phone") || "New message",
    text: String(m.text || "").slice(0, 300),
  }, 24 * 60 * 60 * 1000);
});
