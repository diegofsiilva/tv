package io.canais;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** The categories the person hid from the chips, per kind (by id): Seções brings them back. */
final class Hidden {
    private Hidden() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("hidden", Context.MODE_PRIVATE);
    }

    static Set<String> get(Context c, int kind) {
        return new HashSet<>(sp(c).getStringSet("k" + kind, new HashSet<>()));
    }

    static void set(Context c, int kind, Set<String> ids) {
        sp(c).edit().putStringSet("k" + kind, new HashSet<>(ids)).apply();
    }

    static void hide(Context c, int kind, String id) {
        Set<String> ids = get(c, kind);
        ids.add(id);
        set(c, kind, ids);
    }
}
