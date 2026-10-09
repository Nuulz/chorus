package dev.nuulz.chorus;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** A properties file in config/, written with its defaults the first time. */
public final class Props {
    private final Properties p = new Properties();

    public Props(Path file, Properties defaults) {
        p.putAll(defaults);
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) { p.load(r); }
            } else {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(file)) { defaults.store(w, "Chorus"); }
            }
        } catch (IOException e) {
            Chorus.LOG.warn("chorus: couldn't read {} ({}), using defaults", file, e.toString());
        }
    }

    public String str(String key) { return p.getProperty(key, "").trim(); }

    public int num(String key, int min, int max) {
        try { return Math.clamp(Integer.parseInt(str(key)), min, max); }
        catch (NumberFormatException e) { return min; }
    }

    public boolean flag(String key) { return Boolean.parseBoolean(str(key)); }
}
