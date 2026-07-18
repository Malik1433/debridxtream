/**
 * Reseller credit engine (callable Cloud Functions).
 *
 * These are the ONLY licensing writes that must be server-trusted: a reseller
 * activating or renewing a client has to spend credits AND flip the device's
 * licence in one atomic step. Firestore rules forbid a reseller from writing
 * either their own credit balance or a licence's entitlement fields, so both
 * paths run here under the admin SDK inside a transaction.
 *
 * Everything else (reseller self-signup, owner credit top-ups, all reads) is
 * handled by security rules alone — no function needed.
 *
 * Deploy needs the Firebase project on the Blaze plan:
 *   cd admin-panel && firebase deploy --only functions
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();
const db = admin.firestore();

const MONTH_MS = 30 * 24 * 60 * 60 * 1000;

/** Shared credit-spend transaction for both activate and renew. */
async function spendAndApply({ callerUid, licenseRef, planId, requireOwnedByCaller }) {
  return db.runTransaction(async (tx) => {
    const resellerRef = db.collection("resellers").doc(callerUid);
    const planRef = db.collection("plans").doc(planId);

    // All reads first (Firestore transaction rule).
    const [licenseSnap, planSnap, resellerSnap] = await Promise.all([
      tx.get(licenseRef),
      tx.get(planRef),
      tx.get(resellerRef),
    ]);

    if (!resellerSnap.exists) {
      throw new HttpsError("failed-precondition", "Reseller account not found.");
    }
    const reseller = resellerSnap.data();
    if (reseller.status !== "active") {
      throw new HttpsError("permission-denied", "Your reseller account is not active.");
    }
    if (!planSnap.exists) {
      throw new HttpsError("not-found", "Unknown plan.");
    }
    const plan = planSnap.data();
    const cost = Number(plan.cost);
    const months = Number(plan.months);
    if (!Number.isFinite(cost) || !Number.isFinite(months) || cost < 0 || months <= 0) {
      throw new HttpsError("internal", "Plan is misconfigured.");
    }
    if (!licenseSnap.exists) {
      throw new HttpsError(
        "not-found",
        "No device found for that activation code. Ask the customer to open the app first."
      );
    }
    const license = licenseSnap.data();

    // Ownership: activate allows an UNCLAIMED device or one already yours; renew
    // requires it to already be yours. Never touch another reseller's client.
    const ownedByOther = license.resellerId && license.resellerId !== callerUid;
    if (ownedByOther) {
      throw new HttpsError("permission-denied", "This device belongs to another reseller.");
    }
    if (requireOwnedByCaller && license.resellerId !== callerUid) {
      throw new HttpsError("permission-denied", "You can only renew your own clients.");
    }

    const credits = Number(reseller.credits) || 0;
    if (credits < cost) {
      throw new HttpsError(
        "failed-precondition",
        `Not enough credits (need ${cost}, have ${credits}).`
      );
    }

    // Extend from the later of now / current expiry so renewing early never loses time.
    const now = Date.now();
    const base = Math.max(now, Number(license.expiresAt) || 0);
    const newExpiry = base + months * MONTH_MS;
    const balanceAfter = credits - cost;

    tx.update(licenseRef, {
      status: "active",
      tier: plan.tier,
      expiresAt: newExpiry,
      resellerId: callerUid,
      resellerEmail: reseller.email || null,
      planId: planId,
      activatedAt: now,
    });
    tx.update(resellerRef, {
      credits: balanceAfter,
      clientCount: admin.firestore.FieldValue.increment(license.resellerId ? 0 : 1),
    });
    tx.set(db.collection("credit_ledger").doc(), {
      resellerId: callerUid,
      delta: -cost,
      reason: requireOwnedByCaller ? "renew" : "activate",
      licenseId: licenseRef.id,
      planId: planId,
      balanceAfter: balanceAfter,
      at: now,
    });

    return { installId: licenseRef.id, tier: plan.tier, expiresAt: newExpiry, creditsLeft: balanceAfter };
  });
}

exports.activateClient = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in required.");
  const activationCode = (req.data && req.data.activationCode || "").trim().toUpperCase();
  const planId = req.data && req.data.planId;
  if (!activationCode || !planId) {
    throw new HttpsError("invalid-argument", "activationCode and planId are required.");
  }

  // The device doc id is the installId, not the code — look it up by activationCode.
  const q = await db.collection("licenses")
    .where("activationCode", "==", activationCode).limit(1).get();
  if (q.empty) {
    throw new HttpsError(
      "not-found",
      "No device found for that activation code. Ask the customer to open the app first."
    );
  }
  return spendAndApply({
    callerUid: uid,
    licenseRef: q.docs[0].ref,
    planId,
    requireOwnedByCaller: false,
  });
});

exports.renewClient = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in required.");
  const installId = req.data && req.data.installId;
  const planId = req.data && req.data.planId;
  if (!installId || !planId) {
    throw new HttpsError("invalid-argument", "installId and planId are required.");
  }
  return spendAndApply({
    callerUid: uid,
    licenseRef: db.collection("licenses").doc(installId),
    planId,
    requireOwnedByCaller: true,
  });
});
