package dev.nuulz.chorus;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which pages a screen may show: https only, and the host must be (a subdomain of) an allowed one.
 * The server checks it before storing a URL and every client checks it again before loading one,
 * so a modified server can't point everyone's Chromium at an arbitrary page.
 */
public final class UrlPolicy {
    public static final String DEFAULT_HOSTS =
        "youtube.com,youtu.be,youtube-nocookie.com,twitch.tv,vimeo.com,dailymotion.com,kick.com,archive.org";
    public static final int MAX_LENGTH = 512;

    private final List<String> hosts;

    public UrlPolicy(String csv) {
        hosts = Arrays.stream(csv.split(",")).map(s -> s.trim().toLowerCase(Locale.ROOT))
            .filter(s -> !s.isEmpty()).toList();
    }

    public boolean allows(String url) {
        if (url == null || url.isEmpty() || url.length() > MAX_LENGTH) return false;
        try {
            URI u = new URI(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null) return false;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            return hosts.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        } catch (Exception e) {
            return false;
        }
    }
}
