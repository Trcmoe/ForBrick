package dev.forbrick.core;

/** A locally present launcher version. */
public final class VersionInfo {
    public final String id;
    public final String mainClass;
    public final boolean installed;

    public VersionInfo(String id, String mainClass, boolean installed) {
        this.id = id;
        this.mainClass = mainClass;
        this.installed = installed;
    }

    @Override public String toString() {
        return id + (installed ? " (ForBrick installed)" : "")
                + " — " + (mainClass == null ? "inherited main class" : mainClass);
    }
}
