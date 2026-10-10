package dev.nuulz.chorus.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.TreeSet;

/** Titles of the windows on screen (Windows), for gdigrab to capture one of them by name. */
final class WindowList {
    private static volatile List<String> titles = List.of();
    private static volatile boolean loading;

    static List<String> titles() { return titles; }
    static boolean loading() { return loading; }

    /** Refreshes in the background; the next screen open (or a refresh) shows the new list. */
    static void refresh() {
        if (loading) return;
        loading = true;
        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                    "Get-Process | Where-Object { $_.MainWindowTitle } | ForEach-Object { $_.MainWindowTitle }")
                    .redirectErrorStream(true).start();
                TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String l; (l = r.readLine()) != null; ) if (!l.isBlank()) set.add(l.trim());
                }
                p.waitFor();
                titles = List.copyOf(set);
            } catch (Exception ignored) {
                titles = List.of();
            } finally {
                loading = false;
            }
        }, "chorus-windows");
        t.setDaemon(true);
        t.start();
    }

    private WindowList() {}
}
