package com.lightbox.app.abi;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, stateless policy module for the guided GameGuardian import UX
 * introduced in v1.0.2.
 *
 * <p>The engine side of GG support lives in Bcore's RuntimeHook /
 * FileSystemHook / GGDaemon surface and is frozen — see
 * {@code .github/workflows/android-build.yml}'s {@code gg-guard} job.
 * This class is strictly UI/UX: it identifies a GG APK, inspects which
 * sandboxes already hold it, and picks the right dialog copy. It does not
 * touch install logic, it does not touch native code, and it does not try
 * to automate GG's installer mode selection — GG is closed-source and
 * redistributing/mutating its state is out of scope.
 *
 * <p>The existing dual-install path in {@code MainActivity} is the fallback.
 * If the user dismisses the dialog, callers MUST continue down the v1.0.1
 * mixed-ABI code path. This module never regresses behaviour; it only
 * adds a guided step in front of it.
 */
public final class GameGuardianHint {

    /**
     * Package names known to belong to a GameGuardian distribution.
     *
     * <p>Keep this list conservative. We match on exact equality — not
     * contains / prefix — so a false positive on a user's random APK is
     * effectively impossible. When new GG distribution package names surface,
     * add them here and ship a new release; callers ask the whitelist, not
     * the other way around.
     */
    private static final Set<String> KNOWN_PACKAGES;
    static {
        Set<String> s = new HashSet<>(4);
        s.add("com.gameguardian.gg"); // Google Play distribution (historical)
        s.add("cn.mm.gg");            // Official site distribution
        s.add("org.gameguardian");    // Historical alternate id
        KNOWN_PACKAGES = Collections.unmodifiableSet(s);
    }

    /**
     * Substrings we tolerate in a bundle/file name to classify it as a GG
     * import before we've parsed a manifest. Only used as a weak signal on
     * the XAPK/APKS/APKM path — the APK path still uses the authoritative
     * packageName check. False positives here just trigger the dialog;
     * cancelling the dialog reverts to the standard dual-install flow.
     */
    private static final String[] FILENAME_HINTS = {
            "gameguardian",
            "gamegurdian", // common misspelling of GG redistribution filenames
            "game_guardian"
    };

    /** Current-state enumeration that drives dialog copy selection. */
    public enum State {
        /** GG is in neither sandbox — offer the full guided dual install. */
        FRESH,
        /** GG is in main (64-bit) only — offer to add to 32-bit helper. */
        ADD_TO_HELPER,
        /** GG is in helper (32-bit) only — offer to add to 64-bit main. */
        ADD_TO_MAIN,
        /** GG already in both sandboxes — offer to reinstall/update in both. */
        REINSTALL_BOTH
    }

    private GameGuardianHint() {}

    /**
     * Exact-match identity check against {@link #KNOWN_PACKAGES}.
     * Null-safe; returns false for {@code null} or empty input.
     */
    public static boolean isGameGuardianPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        return KNOWN_PACKAGES.contains(packageName);
    }

    /**
     * Filename-based weak hint for the bundle path, used only when we have
     * not yet parsed a base APK out of the bundle. The caller is expected
     * to combine this with an explicit user-confirm dialog so a false
     * positive cannot silently change install routing.
     */
    public static boolean filenameLooksLikeGameGuardian(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String hint : FILENAME_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    /**
     * Build the four-state current-install picture from the two booleans the
     * caller can cheaply determine (main-sandbox presence + helper-sandbox
     * presence). Pure function, no I/O.
     */
    public static State classify(boolean presentInMain, boolean presentInHelper) {
        if (presentInMain && presentInHelper) return State.REINSTALL_BOTH;
        if (presentInMain) return State.ADD_TO_HELPER;
        if (presentInHelper) return State.ADD_TO_MAIN;
        return State.FRESH;
    }

    // ---------------------------------------------------------------------
    // Dialog copy. Centralised here so the wording is one-source-of-truth
    // across APK / bundle / resume paths. English only for now; these strings
    // intentionally live in Java (not strings.xml) because they describe a
    // specific third-party installer's UI and must stay verbatim in sync with
    // labels GG actually displays to the user. If GG relabels its modes,
    // update this file and cut a new release.
    // ---------------------------------------------------------------------

    public static final String DIALOG_TITLE = "Install GameGuardian";

    /**
     * Persistent footnote appended to every dialog variant. Documents the
     * service-context caveat the user has to work around inside GG itself.
     */
    public static final String SERVICE_CONTEXT_FOOTNOTE =
            "\n\nNote: GameGuardian's process list uses Service context by default,"
            + " which can't load inside virtual sandboxes. If GG shows an empty"
            + " process list, tap Application context in GG and rescan.";

    /**
     * Returns the body text for the given state. Appends the
     * {@link #SERVICE_CONTEXT_FOOTNOTE} automatically.
     */
    public static String messageFor(State state) {
        String body;
        switch (state) {
            case FRESH:
                body = "LightBox-NG will install two GameGuardian copies:"
                        + "\n\n  - One in the 64-bit space (for arm64 games)"
                        + "\n  - One in the 32-bit space (for armv7 games)"
                        + "\n\nWhen GameGuardian's installer appears, choose:"
                        + "\n\n  - For the 64-bit copy: Default"
                        + "\n  - For the 32-bit copy: 32-bit games in"
                        + " 64-bit virtual space";
                break;
            case ADD_TO_HELPER:
                body = "GameGuardian is already installed in the 64-bit space."
                        + "\n\nAdd a second copy to the 32-bit space so you can"
                        + " target armv7 games?"
                        + "\n\nWhen GameGuardian's installer appears, choose"
                        + " 32-bit games in 64-bit virtual space.";
                break;
            case ADD_TO_MAIN:
                body = "GameGuardian is already installed in the 32-bit space."
                        + "\n\nAdd a second copy to the 64-bit space so you can"
                        + " target arm64 games?"
                        + "\n\nWhen GameGuardian's installer appears, choose"
                        + " Default.";
                break;
            case REINSTALL_BOTH:
            default:
                body = "GameGuardian is already present in both the 64-bit and"
                        + " 32-bit spaces."
                        + "\n\nReinstall (update) both copies?"
                        + "\n\nFor the 64-bit copy pick Default; for the 32-bit"
                        + " copy pick 32-bit games in 64-bit virtual space.";
                break;
        }
        return body + SERVICE_CONTEXT_FOOTNOTE;
    }

    /** Positive-button label for the given state. */
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
