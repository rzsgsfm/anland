package com.anland.consumer;

import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RootSettings {
    static final String ACCESSIBILITY_COMPONENT =
            "com.anland.consumer/com.anland.consumer.KeyInterceptor";
    private static final String TAG = "AnlandRoot";

    static final class Result {
        final boolean success;
        final String message;

        Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    private RootSettings() {}

    static Result setAccessibilityEnabled(boolean enable) {
        Result current = runRoot("settings get secure enabled_accessibility_services");
        if (!current.success) return current;

        List<String> services = new ArrayList<>();
        String value = current.message.trim();
        if (!value.isEmpty() && !"null".equals(value))
            services.addAll(Arrays.asList(value.split(":")));
        services.removeIf(ACCESSIBILITY_COMPONENT::equals);
        String withoutAnland = TextUtils.join(":", services);
        if (enable) services.add(ACCESSIBILITY_COMPONENT);

        String updated = TextUtils.join(":", services);
        String command;
        if (enable) {
            // Toggling the component forces ColorOS to rebind a service that it marked
            // crashed after an APK update or force-stop. Other services stay enabled.
            command = "settings put secure enabled_accessibility_services "
                    + shellQuote(withoutAnland)
                    + "; settings put secure enabled_accessibility_services "
                    + shellQuote(updated)
                    + "; settings put secure accessibility_enabled 1";
        } else {
            command = "settings put secure enabled_accessibility_services "
                    + shellQuote(updated);
        }
        Result write = runRoot(command);
        if (!write.success) return write;

        Result verify = runRoot("settings get secure enabled_accessibility_services");
        boolean present = verify.success && Arrays.asList(verify.message.trim().split(":"))
                .contains(ACCESSIBILITY_COMPONENT);
        return present == enable
                ? new Result(true, enable ? "enabled" : "disabled")
                : new Result(false, "Accessibility setting verification failed");
    }

    static Result setSystemBarsBlocked(boolean blocked) {
        String value = blocked
                ? "{\"enable\":0,\"curState\":0,\"curList\":[]}"
                : "{\"enable\":1,\"curState\":0,\"curList\":[]}";
        if (blocked) {
            return runRoot("cmd statusbar send-disable-flag none; "
                    + "settings put global statusbar_input_transfer " + shellQuote(value) + "; "
                    + "cmd statusbar collapse; sleep 1; "
                    + "cmd statusbar send-disable-flag "
                    + "statusbar-expansion quick-settings home recents; "
                    + "settings put global show_gamespace_edge_panel 0; "
                    + "settings put secure show_gamespace_edge_panel 0; "
                    + "settings put secure "
                    + "com.anland.consumeroplus_games_navigation_prevent_mistaken_touch_switch_key 1; "
                    + "pm disable-user --user 0 com.oplus.games >/dev/null; "
                    + "settings put global policy_control "
                    + shellQuote("immersive.full=com.anland.consumer"));
        }
        return runRoot("cmd statusbar send-disable-flag none; "
                + "settings put global statusbar_input_transfer " + shellQuote(value) + "; "
                + "settings put global show_gamespace_edge_panel 1; "
                + "settings put secure show_gamespace_edge_panel 1; "
                + "settings put secure "
                + "com.anland.consumeroplus_games_navigation_prevent_mistaken_touch_switch_key 0; "
                + "pm enable --user 0 com.oplus.games >/dev/null; "
                + "if [ \"$(settings get global policy_control)\" = "
                + shellQuote("immersive.full=com.anland.consumer") + " ]; then "
                + "settings delete global policy_control; fi");
    }

    private static Result runRoot(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "Root command timed out");
            }
            String output = readAll(process.getInputStream()).trim();
            if (process.exitValue() != 0) {
                Log.w(TAG, "Root command failed: " + output);
                return new Result(false, output.isEmpty() ? "Root access denied" : output);
            }
            return new Result(true, output);
        } catch (Exception e) {
            Log.w(TAG, "Root command failed", e);
            return new Result(false, e.getMessage() == null ? "Root command failed" : e.getMessage());
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
