package tv.retwitch.mod;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves a recording to the {@link RecordPlayer}'s IVS as an HLS VOD on 127.0.0.1 (cleartext there is allowed by
 * {@code LocalhostCleartextPatch}): {@code <secret>/<id>/index.m3u8} from its segment list, {@code init.mp4} and
 * {@code s/<n>} as byte ranges of the file. The player gets the exact length and seeks a .ts file like any VOD.
 */
final class RecordServer {

    private static final String TAG = "RTRecordServer";
    private static final String HOST = "127.0.0.1";
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "RTRecordServer");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static ServerSocket server;
    private static String secret;

    private RecordServer() {
    }

    private static final class Entry {
        final Uri file;
        final Records.Index index;
        final long initBytes;
        final boolean fmp4;

        Entry(Uri file, Records.Index index, long initBytes, boolean fmp4) {
            this.file = file;
            this.index = index;
            this.initBytes = initBytes;
            this.fmp4 = fmp4;
        }
    }

    /** The recording's playlist URL; it is served until {@link #unpublish}. */
    static Uri publish(String id, Uri file, Records.Index index, long initBytes, boolean fmp4) throws IOException {
        ENTRIES.put(id, new Entry(file, index, initBytes, fmp4));
        return Uri.parse(base() + id + "/index.m3u8");
    }

    static void unpublish(String id) {
        ENTRIES.remove(id);
    }

    private static synchronized String base() throws IOException {
        if (server == null || server.isClosed()) {
            final ServerSocket socket = new ServerSocket(0, 16, InetAddress.getByName(HOST));
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            StringBuilder hex = new StringBuilder();
            for (byte b : random) {
                hex.append(String.format("%02x", b));
            }
            secret = hex.toString();
            server = socket;
            Thread acceptor = new Thread(() -> {
                try {
                    while (!socket.isClosed()) {
                        final Socket client = socket.accept();
                        WORKERS.execute(() -> handle(client));
                    }
                } catch (IOException e) {
                    Log.w(TAG, "accept loop ended: " + e);
                }
            }, "RTRecordAccept");
            acceptor.setDaemon(true);
            acceptor.start();
        }
        return "http://" + HOST + ":" + server.getLocalPort() + "/" + secret + "/";
    }

    private static void handle(Socket client) {
        try {
            client.setSoTimeout(15_000);
            InputStream in = client.getInputStream();
            String request = readLine(in);
            for (String header = readLine(in); header != null && !header.isEmpty(); header = readLine(in)) {
                // headers: nothing we need (a Range gets the whole part, which a player accepts)
            }
            String[] parts = request == null ? new String[0] : request.split(" ");
            boolean head = parts.length > 0 && "HEAD".equals(parts[0]);
            List<String> path = parts.length > 1 ? Uri.parse("http://" + HOST + parts[1]).getPathSegments() : null;
            Entry entry = path != null && path.size() >= 3 && path.get(0).equals(secret) ? ENTRIES.get(path.get(1)) : null;
            OutputStream out = client.getOutputStream();
            if (entry == null) {
                header(out, 404, "text/plain", 0);
                return;
            }
            String name = path.get(2);
            if ("index.m3u8".equals(name)) {
                byte[] body = Records.playlist(entry.index, entry.fmp4).getBytes(StandardCharsets.UTF_8);
                header(out, 200, "application/vnd.apple.mpegurl", body.length);
                if (!head) {
                    out.write(body);
                }
            } else if ("init.mp4".equals(name)) {
                slice(out, entry, 0, entry.initBytes, "video/mp4", head);
            } else if ("s".equals(name) && path.size() == 4) {
                String file = path.get(3);
                int n = Integer.parseInt(file.substring(0, file.indexOf('.')));
                if (n < 0 || n >= entry.index.count) {
                    header(out, 404, "text/plain", 0);
                    return;
                }
                slice(out, entry, entry.initBytes + entry.index.offset[n], entry.index.bytes[n],
                        entry.fmp4 ? "video/mp4" : "video/mp2t", head);
            } else {
                header(out, 404, "text/plain", 0);
            }
            out.flush();
        } catch (Throwable t) {
            Log.w(TAG, "request failed: " + t);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // gone already
            }
        }
    }

    private static void slice(OutputStream out, Entry entry, long offset, long length, String type, boolean head)
            throws IOException {
        try (ParcelFileDescriptor fd = AppContext.get().getContentResolver().openFileDescriptor(entry.file, "r")) {
            if (fd == null) {
                throw new IOException("can't open " + entry.file);
            }
            header(out, 200, type, length);
            if (head) {
                return;
            }
            try (FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
                in.getChannel().position(offset);
                byte[] buffer = new byte[64 * 1024];
                long left = length;
                while (left > 0) {
                    int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n <= 0) {
                        break;
                    }
                    out.write(buffer, 0, n);
                    left -= n;
                }
            }
        }
    }

    private static void header(OutputStream out, int code, String type, long length) throws IOException {
        String status = code == 200 ? "200 OK" : "404 Not Found";
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') {
                return b.toString();
            }
            if (c != '\r') {
                b.append((char) c);
            }
        }
        return b.length() > 0 ? b.toString() : null;
    }
}
