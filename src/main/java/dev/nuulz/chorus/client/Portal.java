package dev.nuulz.chorus.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.freedesktop.dbus.DBusPath;
import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.messages.DBusSignal;
import org.freedesktop.dbus.types.UInt32;
import org.freedesktop.dbus.types.Variant;

/**
 * The desktop's screen-cast portal (Wayland): asks the user which window or screen to share, through
 * the compositor's own picker, and gets back a PipeWire node to read it from. The session stays open
 * while casting; closing it ends the share.
 */
final class Portal implements AutoCloseable {
    @DBusInterfaceName("org.freedesktop.portal.ScreenCast")
    public interface ScreenCast extends DBusInterface {
        DBusPath CreateSession(Map<String, Variant<?>> options);
        DBusPath SelectSources(DBusPath session, Map<String, Variant<?>> options);
        DBusPath Start(DBusPath session, String parentWindow, Map<String, Variant<?>> options);
    }

    @DBusInterfaceName("org.freedesktop.portal.Request")
    public interface Request extends DBusInterface {
        class Response extends DBusSignal {
            final UInt32 response;
            final Map<String, Variant<?>> results;

            public Response(String path, UInt32 response, Map<String, Variant<?>> results) throws DBusException {
                super(path, response, results);
                this.response = response;
                this.results = results;
            }
        }
    }

    @DBusInterfaceName("org.freedesktop.portal.Session")
    public interface Session extends DBusInterface {
        void Close();
    }

    private static final String BUS = "org.freedesktop.portal.Desktop";
    private static final String PATH = "/org/freedesktop/portal/desktop";
    private static int serial;

    private final DBusConnection bus;
    private DBusPath session;
    /** Lets the next share skip the picker (the portal remembers the choice). */
    String restoreToken;
    int node = -1;

    Portal() throws DBusException {
        bus = DBusConnectionBuilder.forSessionBus().build();
    }

    /** Opens the picker and waits for the choice; false if the user cancelled. */
    boolean pick(String previousToken) throws Exception {
        ScreenCast sc = bus.getRemoteObject(BUS, PATH, ScreenCast.class);
        Map<String, Variant<?>> created = call(t -> sc.CreateSession(options(t, "session_handle_token", new Variant<>("chorus" + t))));
        if (created == null) return false;
        session = new DBusPath(String.valueOf(created.get("session_handle").getValue()));

        Map<String, Variant<?>> select = new HashMap<>();
        select.put("types", new Variant<>(new UInt32(1 | 2)));       // a monitor or a window
        select.put("multiple", new Variant<>(false));
        select.put("cursor_mode", new Variant<>(new UInt32(2)));     // pointer drawn into the picture
        select.put("persist_mode", new Variant<>(new UInt32(2)));    // remember until revoked
        if (previousToken != null && !previousToken.isEmpty()) select.put("restore_token", new Variant<>(previousToken));
        if (call(t -> sc.SelectSources(session, with(select, t))) == null) return false;

        Map<String, Variant<?>> started = call(t -> sc.Start(session, "", options(t, null, null)));
        if (started == null) return false;
        Variant<?> token = started.get("restore_token");
        restoreToken = token == null ? null : String.valueOf(token.getValue());
        node = firstNumber(started.get("streams").getValue());
        return node >= 0;
    }

    private interface Call { DBusPath run(String token) throws Exception; }

    /** Makes a portal call and waits for its Response signal; null when cancelled. */
    private Map<String, Variant<?>> call(Call c) throws Exception {
        String token = "chorus" + (++serial) + "_" + ProcessHandle.current().pid();
        String sender = bus.getUniqueName().substring(1).replace('.', '_');
        String expected = PATH + "/request/" + sender + "/" + token;
        CompletableFuture<Request.Response> done = new CompletableFuture<>();
        DBusSigHandler<Request.Response> h = r -> { if (r.getPath().equals(expected)) done.complete(r); };
        bus.addSigHandler(Request.Response.class, h);
        try {
            c.run(token);
            Request.Response r = done.get(5, TimeUnit.MINUTES);
            return r.response.intValue() == 0 ? r.results : null;
        } finally {
            bus.removeSigHandler(Request.Response.class, h);
        }
    }

    private static Map<String, Variant<?>> options(String token, String key, Variant<?> value) {
        Map<String, Variant<?>> m = new HashMap<>();
        m.put("handle_token", new Variant<>(token));
        if (key != null) m.put(key, value);
        return m;
    }

    private static Map<String, Variant<?>> with(Map<String, Variant<?>> m, String token) {
        Map<String, Variant<?>> c = new HashMap<>(m);
        c.put("handle_token", new Variant<>(token));
        return c;
    }

    /** The node id inside "streams" (a(ua{sv})), whatever shape the binding gave it. */
    private static int firstNumber(Object o) {
        if (o instanceof UInt32 u) return u.intValue();
        if (o instanceof Number n) return n.intValue();
        if (o instanceof Variant<?> v) return firstNumber(v.getValue());
        if (o instanceof Object[] a) { for (Object x : a) { int n = firstNumber(x); if (n >= 0) return n; } }
        if (o instanceof List<?> l) { for (Object x : l) { int n = firstNumber(x); if (n >= 0) return n; } }
        if (o instanceof org.freedesktop.dbus.Struct s) return firstNumber(s.getParameters());
        return -1;
    }

    @Override
    public void close() {
        try { if (session != null) bus.getRemoteObject(BUS, session.getPath(), Session.class).Close(); } catch (Exception ignored) { }
        try { bus.close(); } catch (Exception ignored) { }
    }
}
