package android.provider;

import android.content.ContentResolver;

public final class MiuiSettings {
    private MiuiSettings() {
    }

    public static final class System {
        private System() {
        }

        public static int getInt(ContentResolver resolver, String name, int def) {
            return Settings.System.getInt(resolver, name, def);
        }

        public static boolean getBoolean(ContentResolver resolver, String name, boolean def) {
            return Settings.System.getInt(resolver, name, def ? 1 : 0) != 0;
        }

        public static String getString(ContentResolver resolver, String name) {
            return Settings.System.getString(resolver, name);
        }
    }
}
