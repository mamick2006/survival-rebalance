package survivalrebalance;

/**
 * The rule for a supported mod whose installed version is not one its patch was tested on.
 * The released tool skips such a mod: apply changes none of its files and none of its saved
 * settings, so nobody reports a half-matching patch to the mod's author. There is no option,
 * flag or file that changes this.
 */
final class VersionPolicy {
    private VersionPolicy() {}

    /** True: apply leaves a mod of an untested version as it is. */
    static boolean skipsOtherVersions() { return true; }

    /** Words shown after the version number for a build that is not the release; null here. */
    static String buildNote() { return null; }

    /** Why update and the update check are off in this build; null here, so they work. */
    static String updateOff() { return null; }
}
