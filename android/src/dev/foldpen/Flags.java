package dev.foldpen;

/** Build-time switches. nix/apk.nix rewrites this file from its arguments
 *  (`apk.override { fingerPen = false; }`); these are the defaults. */
final class Flags {
    /** false: a finger is never the pen. Fingers are always touchscreen
     *  contacts, the finger-mode swipe is refused, and only the S Pen draws. */
    static final boolean FINGER_PEN = true;

    private Flags() { }
}
