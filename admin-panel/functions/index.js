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

/** Fallback for a subscription written before deviceLimit existed (§7.8: the owner chose 3). */
const DEFAULT_DEVICE_LIMIT = 3;

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

/* ════════════════════════════════════════════════════════════════════════
 * END-USER DEVICE CLAIMING — §7 U4 of docs/reports/ANTI_PIRACY_DECISION.md
 *
 * Two sales channels meet here (§7.0) and the difference is the whole design:
 *
 *   reseller channel  — pays PER DEVICE, no limit. A device they activated is
 *                       bound to a customer account for MANAGEMENT ONLY: the
 *                       customer gets to edit its playlists, and no entitlement
 *                       field is touched, because a customer must not be able to
 *                       alter what a reseller sold.
 *   consumer channel  — a subscription covering 3 devices. Claiming consumes a
 *                       slot and PROJECTS the subscription's entitlement onto the
 *                       licence, in the same fields the TV already reads.
 *
 * Slot counting is here rather than in rules for the plain reason that rules
 * cannot count.
 * ════════════════════════════════════════════════════════════════════════ */

/** Deletes bindings that point at this installId under a stale anonymous uid. */
function pruneStaleBindings(tx, bindingSnaps, keepAuthUid) {
  bindingSnaps.forEach((d) => {
    if (d.id !== keepAuthUid) tx.delete(d.ref);
  });
}

exports.claimDevice = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in required.");
  const activationCode = ((req.data && req.data.activationCode) || "").trim().toUpperCase();
  const deviceName = ((req.data && req.data.deviceName) || "").trim().slice(0, 60);
  if (!activationCode) {
    throw new HttpsError("invalid-argument", "activationCode is required.");
  }

  const q = await db.collection("licenses")
    .where("activationCode", "==", activationCode).limit(1).get();
  if (q.empty) {
    throw new HttpsError(
      "not-found",
      "No TV found for that code. Open the app on your TV and check the code on screen."
    );
  }
  const licenseRef = q.docs[0].ref;
  const installId = licenseRef.id;

  return db.runTransaction(async (tx) => {
    // ---- reads first (Firestore transaction rule) ----
    const [licenseSnap, identitySnap, subsSnap, bindingsSnap] = await Promise.all([
      tx.get(licenseRef),
      tx.get(db.collection("device_identity").doc(installId)),
      tx.get(db.collection("subscriptions")
        .where("ownerUid", "==", uid).where("status", "==", "active").limit(1)),
      tx.get(db.collection("device_auth").where("installId", "==", installId)),
    ]);

    if (!licenseSnap.exists) throw new HttpsError("not-found", "That TV is no longer registered.");
    const license = licenseSnap.data();

    if (license.ownerUid && license.ownerUid !== uid) {
      throw new HttpsError(
        "permission-denied",
        "That TV is already linked to a different account."
      );
    }

    // A TV that has never signed in has no identity yet. Claim anyway: the customer paid, and
    // refusing here would leave them unable to watch over a background detail. The binding that
    // lets it read playlists is simply deferred, and the caller is told so.
    const authUid = identitySnap.exists ? identitySnap.data().authUid : null;

    const patch = { ownerUid: uid };
    if (deviceName) patch.deviceName = deviceName;

    let subscriptionId = license.subscriptionId || null;

    // A device can already be entitled WITHOUT a consumer subscription in two ways: a
    // reseller activated it, or staff activated it straight from the admin panel. Both are
    // management-binding-only claims — the entitlement exists already, so linking must not
    // demand a subscription and must not touch status/tier/expiresAt.
    //
    // Missing the admin case made every admin-activated device unlinkable: it fell into the
    // consumer branch below and answered "You don't have an active subscription yet" to a
    // customer whose TV was, in fact, already active.
    const entitledOutsideSubscription =
      Boolean(license.resellerId) ||
      (license.status === "active" && !license.subscriptionId);

    if (entitledOutsideSubscription) {
      // Management binding only. Deliberately no status/tier/expiresAt here.
      subscriptionId = null;
    } else {
      if (subsSnap.empty) {
        throw new HttpsError(
          "failed-precondition",
          "You don't have an active subscription yet. Ask your provider to activate this TV."
        );
      }
      const subDoc = subsSnap.docs[0];
      const sub = subDoc.data();
      const limit = Number(sub.deviceLimit) || DEFAULT_DEVICE_LIMIT;

      if (license.subscriptionId !== subDoc.id) {
        // Only a NEW device costs a slot; re-claiming one already on this subscription (a
        // reinstall, a second scan of the same QR) must be free or a customer could lock
        // themselves out by scanning twice.
        const usedSnap = await tx.get(
          db.collection("licenses").where("subscriptionId", "==", subDoc.id)
        );
        if (usedSnap.size >= limit) {
          throw new HttpsError(
            "resource-exhausted",
            `All ${limit} devices on your subscription are in use. Ask your provider to remove one.`
          );
        }
      }
      subscriptionId = subDoc.id;
      patch.subscriptionId = subDoc.id;
      patch.status = "active";
      patch.tier = sub.tier || "normal";
      patch.expiresAt = Number(sub.expiresAt) || 0;
    }

    // ---- writes ----
    tx.update(licenseRef, patch);
    if (authUid) {
      tx.set(db.collection("device_auth").doc(authUid), {
        installId,
        ownerUid: uid,
        subscriptionId,
        at: Date.now(),
      });
    }
    // Clearing app data mints a fresh anonymous uid, so an old binding for this TV would otherwise
    // linger and keep reading the account's playlists forever.
    pruneStaleBindings(tx, bindingsSnap.docs, authUid);

    return { installId, identityPending: !authUid, subscriptionId };
  });
});

/**
 * Unlinks a device and frees its slot. NOT available to the customer (§7.8): the owner decided
 * that only staff may free a slot, which is what keeps "3 devices" meaning three devices. The cost
 * is a support ticket per replaced TV, recorded in §7.7.
 */
exports.releaseDevice = onCall(async (req) => {
  const uid = req.auth && req.auth.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Sign in required.");
  const installId = req.data && req.data.installId;
  if (!installId) throw new HttpsError("invalid-argument", "installId is required.");

  const licenseRef = db.collection("licenses").doc(installId);

  return db.runTransaction(async (tx) => {
    const [licenseSnap, adminSnap, bindingsSnap] = await Promise.all([
      tx.get(licenseRef),
      tx.get(db.collection("admins").doc(uid)),
      tx.get(db.collection("device_auth").where("installId", "==", installId)),
    ]);
    if (!licenseSnap.exists) throw new HttpsError("not-found", "Unknown device.");
    const license = licenseSnap.data();

    const isAdmin = adminSnap.exists;
    const isOwningReseller = !!license.resellerId && license.resellerId === uid;
    if (!isAdmin && !isOwningReseller) {
      throw new HttpsError("permission-denied", "Only your provider can remove a device.");
    }

    const patch = {
      ownerUid: admin.firestore.FieldValue.delete(),
      subscriptionId: admin.firestore.FieldValue.delete(),
    };
    // A subscription device was entitled BY that subscription, so releasing it has to take the
    // entitlement back — otherwise a freed slot leaves a device that still plays forever. A
    // reseller-entitled device keeps its entitlement: the reseller sold that separately.
    if (license.subscriptionId && !license.resellerId) patch.status = "inactive";

    tx.update(licenseRef, patch);
    bindingsSnap.docs.forEach((d) => tx.delete(d.ref));
    return { installId, released: true };
  });
});
