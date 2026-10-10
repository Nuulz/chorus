package dev.nuulz.chorus.client;

import java.util.List;
import java.util.TreeSet;

/** Titles of the windows on screen (Windows), for gdigrab to capture one of them by name. */
final class WindowList {
    private static volatile List<String> titles = List.of();
    private static volatile boolean loading;
    private static volatile int version;

    static List<String> titles() { return titles; }
    static boolean loading() { return loading; }
    /** Goes up each time a refresh finishes, so an open screen knows to rebuild its list. */
    static int version() { return version; }

    /** Refreshes in the background; the next screen open (or a refresh) shows the new list. */
    static void refresh() {
        if (loading) return;
        loading = true;
        Thread t = new Thread(() -> {
            try {
                TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                char[] buf = new char[512];
                User32.INSTANCE.EnumWindows((hwnd, data) -> {
                    if (User32.INSTANCE.IsWindowVisible(hwnd) && User32.INSTANCE.GetWindow(hwnd, GW_OWNER) == null) {
                        int n = User32.INSTANCE.GetWindowTextW(hwnd, buf, buf.length);
                        String title = n > 0 ? new String(buf, 0, n).trim() : "";
                        if (!title.isEmpty() && !title.equals("Program Manager")) set.add(title);
                    }
                    return true;
                }, null);
                titles = List.copyOf(set);
            } catch (Throwable e) {
                dev.nuulz.chorus.Chorus.LOG.warn("chorus: couldn't list windows", e);
                titles = List.of();
            } finally {
                loading = false;
                version++;
            }
        }, "chorus-windows");
        t.setDaemon(true);
        t.start();
    }

    private static final int GW_OWNER = 4;

    /** The few user32 calls needed to list the top-level windows. */
    private interface User32 extends com.sun.jna.win32.StdCallLibrary {
        User32 INSTANCE = com.sun.jna.Native.load("user32", User32.class, com.sun.jna.win32.W32APIOptions.UNICODE_OPTIONS);

        interface EnumProc extends com.sun.jna.win32.StdCallLibrary.StdCallCallback {
            boolean callback(com.sun.jna.Pointer hwnd, com.sun.jna.Pointer data);
        }

        boolean EnumWindows(EnumProc proc, com.sun.jna.Pointer data);
        boolean IsWindowVisible(com.sun.jna.Pointer hwnd);
        com.sun.jna.Pointer GetWindow(com.sun.jna.Pointer hwnd, int cmd);
        int GetWindowTextW(com.sun.jna.Pointer hwnd, char[] text, int max);
    }

    private WindowList() {}
}
