package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        setIcon(LauncherIcon.DARKBLUE);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        return true;
    }

    public static void setIcon(LauncherIcon icon) {
        // AuthorGram: тільки одна статична іконка, перемикання вимкнено.
    }

    public enum LauncherIcon {
        DARKBLUE("DarkBlueIcon", R.color.authorgram_launcher_background, R.drawable.ic_launcher_authorgram_foreground, R.string.AppIconDarkBlue);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }

        public boolean isNekoX() {
            return false;
        }
    }
}
