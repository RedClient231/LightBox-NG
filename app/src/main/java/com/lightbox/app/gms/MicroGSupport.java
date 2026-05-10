package com.lightbox.app.gms;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure, stateless policy module for the guided microG GmsCore import UX
 * introduced in v1.0.4 (M1).
 *
 * <p>Scope is deliberately narrow: this class identifies a microG GmsCore
 * APK and owns the dialog copy for the install-time prompt. It does NOT
 * signature-spoof, does NOT route service bindings, and does NOT touch
 * Bcore. That comes in v1.0.5 (M2) only after on-device validation confirms
 * microG actually starts inside the Bcore sandbox.
 *
 * <p>The existing dual-install path in {@code MainActivity} handles the
 * actual APK install — this module only classifies + provides copy. Same
 * pattern as {@link com.lightbox.app.abi.GameGuardianHint}.
 *
 * <h2>Why microG and not real Google Play Services?</h2>
 * Play Services cannot be installed into a Bcore-like sandbox: it depends on
 * system-UID platform hooks that no virtualization environment can grant.
 * microG is a clean-room reimplementation (Apache-2.0) of the Play Services
 * client APIs that runs as a normal user app, which is exactly what a
 * virtual sandbox can host. See https://microg.org and
 * https://github.com/microg/GmsCore for details.
 *
 * <h2>What microG alone (M1) solves</h2>
 * <ul>
 *   <li>Nothing automatically. It's the install path only.</li>
 *   <li>Once M2 ships, games that only need Firebase Analytics / FCM push /
 *       Google Sign-In / Maps / Play Asset Delivery will be able to resolve
 *       those calls through microG.</li>
 * </ul>
 *
 * <h2>What microG cannot solve</h2>
 * <ul>
 *   <li>Play Integrity / SafetyNet attestation (requires DroidGuard, not
 *       shipped here).</li>
 *   <li>Anti-cheat SDKs that bypass GMS (Tencent GCloud, NetEase, Garena,
 *       Level Infinite / Gangstar Mirage).</li>
 *   <li>Games that hardcode Google's server certificate (banking apps,
 *       Pokemon Go-class titles).</li>
 * </ul>
 */
public final class MicroGSupport {

    /**
     * Package names that microG's GmsCore ships under. All three must be
     * treated as "is microG" because microG's own release process produces
     * two variants of the GmsCore APK (the one that spoofs
     * {@code com.google.android.gms} to get the Play Services service-name
     * binding to work, and a neutral {@code org.microg.gms} variant for
     * testing). {@code com.android.vending} is microG's FakeStore companion.
     *
     * <p>We match on exact equality so the import guard cannot false-positive
     * on user packages named "gms" or "vending" in unrelated third-party
     * apps. Current microG release as of 2026-04-24 is v0.3.15.250932
     * (GMS compat level 25.09.32) — see
     * <a href="https://github.com/microg/GmsCore/releases">microG releases</a>.
     */
    private static final Set<String> KNOWN_PACKAGES;
    static {
        Set<String> s = new HashSet<>(4);
        s.add("com.google.android.gms");  // microG GmsCore (spoofed variant)
        s.add("com.android.vending");      // microG FakeStore (optional)
        s.add("org.microg.gms");           // microG GmsCore (neutral variant)
        KNOWN_PACKAGES = Collections.unmodifiableSet(s);
    }

    /** Current-state enumeration driving dialog copy selection. */
    public enum State {
        /** microG absent from both sandboxes — offer the full guided dual install. */
        FRESH,
        /** microG in main (64-bit) only — offer to add to 32-bit helper. */
        ADD_TO_HELPER,
        /** microG in helper (32-bit) only — offer to add to 64-bit main. */
        ADD_TO_MAIN,
        /** microG already in both — offer to reinstall/update. */
        REINSTALL_BOTH
    }

    private MicroGSupport() {}

    /** Exact-match identity check against {@link #KNOWN_PACKAGES}. Null-safe. */
    public static boolean isMicroGPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return KNOWN_PACKAGES.contains(packageName);
    }

    /**
     * Build the four-state current-install picture from the two booleans the
     * caller can cheaply determine. Pure function, no I/O. Mirrors
     * {@link com.lightbox.app.abi.GameGuardianHint#classify} exactly.
     */
    public static State classify(boolean presentInMain, boolean presentInHelper) {
        if (presentInMain && presentInHelper) return State.REINSTALL_BOTH;
        if (presentInMain) return State.ADD_TO_HELPER;
        if (presentInHelper) return State.ADD_TO_MAIN;
        return State.FRESH;
    }

    // ---------------------------------------------------------------------
    // Dialog copy. English only. Same reasoning as GameGuardianHint: these
    // strings describe a third-party installer's UI + intentionally set
    // expectations about what microG does and does not fix, and must stay
    // verbatim in sync with microG's actual behaviour. If microG's UX
    // changes, update this file and cut a new release.
    // ---------------------------------------------------------------------

    public static final String DIALOG_TITLE = "Install microG GmsCore";

    /**
     * Honest footnote about what microG can and cannot do. Appears at the
     * bottom of every dialog variant so a user never misses it.
     */
    public static final String EXPECTATIONS_FOOTNOTE =
            "\n\nmicroG enables Firebase, FCM push, Maps, Google Sign-In, and"
            + " Play Asset Delivery for many games. It does NOT fix:"
            + "\n  - Anti-cheat SDKs (Tencent GCloud, NetEase, Garena)"
            + "\n  - Play Integrity / SafetyNet (banking apps, etc.)"
            + "\n\nSignature spoofing for microG ships in v1.0.5. In v1.0.4"
            + " microG installs but most games will not route through it yet.";

    public static String messageFor(State state) {
        String body;
        switch (state) {
            case FRESH:
                body = "LightBox-NG will install microG GmsCore in both sandboxes:"
                        + "\n\n  - 64-bit space (for arm64 games)"
                        + "\n  - 32-bit space (for armv7 games)"
                        + "\n\nThe microG APK is a universal build so both"
                        + " sandboxes use the same file.";
                break;
            case ADD_TO_HELPER:
                body = "microG is already installed in the 64-bit space."
                        + "\n\nAdd a second copy to the 32-bit space so 32-bit"
                        + " games can also route through microG?";
                break;
            case ADD_TO_MAIN:
                body = "microG is already installed in the 32-bit space."
                        + "\n\nAdd a second copy to the 64-bit space so 64-bit"
                        + " games can also route through microG?";
                break;
            case REINSTALL_BOTH:
            default:
                body = "microG is already present in both the 64-bit and 32-bit"
                        + " spaces."
                        + "\n\nReinstall (update) both copies?";
                break;
        }
        return body + EXPECTATIONS_FOOTNOTE;
    }

    public static String positiveButtonFor(State state) {
        switch (state) {
            case ADD_TO_HELPER: return "Add to 32-bit space";
            case ADD_TO_MAIN:   return "Add to 64-bit space";
            case REINSTALL_BOTH: return "Reinstall both";
            case FRESH:
            default:             return "Install in both";
        }
    }

    /** Immutable accessor for tests / diagnostics. */
    public static Set<String> knownPackages() {
        return KNOWN_PACKAGES;
    }
}
