package app.noam.extension.spotify.localserver;

import android.net.Uri;
import android.util.Base64;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.noam.extension.spotify.Utils;

/**
 * A small WebDAV client, enough to walk a Nextcloud (or any WebDAV) music folder and read bytes out
 * of it. Nextcloud's files live under {@code /remote.php/dav/files/<user>/}, which is filled in
 * automatically when the user gives just the server address.
 */
public final class WebDav {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_DEPTH = 6;

    private static final String[] AUDIO_EXTENSIONS =
            {".mp3", ".m4a", ".aac", ".flac", ".ogg", ".oga", ".opus", ".wav", ".mp4", ".m4b", ".wma"};

    private final String baseUrl;
    private final String username;
    private final String password;

    public WebDav(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl;
        this.username = username;
        this.password = password;
    }

    /**
     * The collection URLs to try, in order, for the folder the user picked.
     *
     * A Nextcloud user normally gives just the server address, whose files actually live under
     * {@code /remote.php/dav/files/<user>/}. Any other WebDAV server serves them straight off the
     * address given. Both are tried rather than guessing from the URL alone.
     */
    public List<String> candidateRoots(String folder) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String path = encodePath(folder);

        List<String> candidates = new ArrayList<>();
        candidates.add(base + path + "/");

        if (!base.contains("/remote.php/") && !base.contains("/dav/")) {
            candidates.add(base + "/remote.php/dav/files/"
                    + Uri.encode(username == null ? "" : username) + path + "/");
        }
        return candidates;
    }

    private static String encodePath(String folder) {
        String path = folder == null ? "" : folder.trim();
        if (path.isEmpty() || path.equals("/")) return "";
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            encoded.append('/').append(Uri.encode(segment));
        }
        return encoded.toString();
    }

    /**
     * Lists every audio file in the user's folder, trying each candidate root until one answers.
     *
     * @throws IOException with the first failure if none of them do.
     */
    public List<RemoteTrack> listFolder(String folder) throws IOException {
        IOException firstFailure = null;

        for (String root : candidateRoots(folder)) {
            try {
                return listAudioFiles(root);
            } catch (IOException ex) {
                if (firstFailure == null) firstFailure = ex;
            }
        }
        throw firstFailure != null ? firstFailure : new IOException("The folder could not be listed");
    }

    /** Recursively lists every audio file below {@code url}. */
    public List<RemoteTrack> listAudioFiles(String url) throws IOException {
        List<RemoteTrack> tracks = new ArrayList<>();
        collect(url, 0, tracks);
        return tracks;
    }

    private void collect(String url, int depth, List<RemoteTrack> tracks) throws IOException {
        if (depth > MAX_DEPTH) return;

        for (Entry entry : propfind(url)) {
            if (entry.isCollection) {
                collect(entry.url, depth + 1, tracks);
            } else if (isAudio(entry.name)) {
                RemoteTrack track = new RemoteTrack();
                track.url = entry.url;
                track.name = entry.name;
                track.size = entry.size;
                tracks.add(track);
            }
        }
    }

    private static boolean isAudio(String name) {
        String lower = name.toLowerCase(Locale.US);
        for (String extension : AUDIO_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    private static final class Entry {
        String url;
        String name;
        long size;
        boolean isCollection;
    }

    private List<Entry> propfind(String url) throws IOException {
        HttpURLConnection connection = open(url);
        setPropfindMethod(connection);
        connection.setRequestProperty("Depth", "1");
        connection.setRequestProperty("Content-Type", "application/xml; charset=utf-8");
        connection.setDoOutput(true);

        String body = "<?xml version=\"1.0\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                + "<d:resourcetype/><d:getcontentlength/><d:getcontenttype/>"
                + "</d:prop></d:propfind>";
        connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

        int status = connection.getResponseCode();
        if (status != 207 && status != 200) {
            connection.disconnect();
            throw new IOException("PROPFIND returned HTTP " + status);
        }

        try (InputStream in = connection.getInputStream()) {
            return parseMultiStatus(in, url);
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Could not read the folder listing: " + ex.getMessage(), ex);
        } finally {
            connection.disconnect();
        }
    }

    private List<Entry> parseMultiStatus(InputStream in, String requestUrl) throws Exception {
        List<Entry> entries = new ArrayList<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(in, null);

        Entry current = null;
        String href = null;
        boolean inResourceType = false;
        String requestPath = Uri.parse(requestUrl).getPath();

        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            String name = parser.getName();
            if (name == null) continue;

            if (event == XmlPullParser.START_TAG) {
                switch (name) {
                    case "response":
                        current = new Entry();
                        href = null;
                        break;
                    case "href":
                        href = parser.nextText().trim();
                        break;
                    case "resourcetype":
                        inResourceType = true;
                        break;
                    case "collection":
                        if (current != null && inResourceType) current.isCollection = true;
                        break;
                    case "getcontentlength":
                        if (current != null) {
                            try {
                                current.size = Long.parseLong(parser.nextText().trim());
                            } catch (Exception ignored) {
                                // A missing or unparsable length only affects the reported size.
                            }
                        }
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (name.equals("resourcetype")) {
                    inResourceType = false;
                } else if (name.equals("response") && current != null && href != null) {
                    String path = Uri.parse(href).getPath();
                    // The collection itself is always returned first; skip it.
                    if (path != null && !equalPaths(path, requestPath)) {
                        current.url = absolute(href);
                        current.name = Uri.decode(lastSegment(path));
                        entries.add(current);
                    }
                    current = null;
                }
            }
        }
        return entries;
    }

    private static boolean equalPaths(String a, String b) {
        return trimSlash(Uri.decode(a)).equals(trimSlash(Uri.decode(b == null ? "" : b)));
    }

    private static String trimSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String lastSegment(String path) {
        String trimmed = trimSlash(path);
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }

    /** Server responses give a path, not a full URL; put the scheme and host back. */
    private String absolute(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        Uri base = Uri.parse(baseUrl);
        String authority = base.getScheme() + "://" + base.getAuthority();
        return authority + (href.startsWith("/") ? href : "/" + href);
    }

    /** Opens a byte range of a remote file. Pass {@code -1} as the end for "until EOF". */
    public HttpURLConnection openRange(String url, long from, long to) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("GET");
        if (from > 0 || to >= 0) {
            connection.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        }
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("GET returned HTTP " + status);
        }
        return connection;
    }

    /**
     * Android's HttpURLConnection is backed by OkHttp, whose setRequestMethod only accepts the
     * standard verbs and rejects PROPFIND outright. The method field it actually sends is the one
     * inherited from HttpURLConnection, so it can be set directly; if even that is blocked, servers
     * built on sabre/dav (which is what Nextcloud and ownCloud use) honour the override header.
     */
    private void setPropfindMethod(HttpURLConnection connection) throws IOException {
        try {
            connection.setRequestMethod("PROPFIND");
            return;
        } catch (ProtocolException expected) {
            // Fall through to the workarounds below.
        }

        try {
            Field method = HttpURLConnection.class.getDeclaredField("method");
            method.setAccessible(true);
            method.set(connection, "PROPFIND");
            return;
        } catch (Exception ex) {
            Utils.log("Could not set the PROPFIND method directly: " + ex);
        }

        connection.setRequestMethod("POST");
        connection.setRequestProperty("X-HTTP-Method-Override", "PROPFIND");
    }

    private HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        if (username != null && !username.isEmpty()) {
            String credentials = username + ":" + (password == null ? "" : password);
            connection.setRequestProperty("Authorization", "Basic "
                    + Base64.encodeToString(credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        }
        return connection;
    }

    public static WebDav fromConfig() {
        return new WebDav(
                ServerConfig.getString(ServerConfig.KEY_URL, ""),
                ServerConfig.getString(ServerConfig.KEY_USERNAME, ""),
                ServerConfig.getString(ServerConfig.KEY_PASSWORD, ""));
    }
}
