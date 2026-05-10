package com.lightbox.app.model;

import android.graphics.drawable.Drawable;

public class ClonedApp {

    private String packageName;
    private String appName;
    private Drawable icon;
    /**
     * True when this row represents a virtual app installed inside the
     * 32-bit helper's sandbox ({@code com.lightbox.ng.arm32}) rather than
     * the main arm64 sandbox. Populated by MainActivity when merging the
     * helper's InstalledGamesProvider cursor into the unified library.
     *
     * The launch path uses this flag to skip main-side ABI inspection (which
     * would report "no libs" because main doesn't own the package) and
     * dispatch directly to the helper's LaunchActivity.
     */
    private boolean ownedByHelper;

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public Drawable getIcon() {
        return icon;
    }

    public void setIcon(Drawable icon) {
        this.icon = icon;
    }

    public boolean isOwnedByHelper() {
        return ownedByHelper;
    }

    public void setOwnedByHelper(boolean ownedByHelper) {
        this.ownedByHelper = ownedByHelper;
    }
}
