package tv.dsh.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网导入服务：给电视一个极简 HTTP 服务，电脑/手机浏览器打开即可上传壁纸。
 *
 * 只有一个页面 + 几个 JSON 接口，不引第三方库：
 *   GET  /                     上传页（来自 assets/www/upload.html）
 *   GET  /api/list             壁纸列表（含当前项）
 *   GET  /api/thumb?name=X     缩略图 JPEG（400px 宽）
 *   POST /api/upload?name=X    原始字节流上传，Content-Length 必填
 *   POST /api/apply?name=X     设为当前壁纸
 *   POST /api/delete?name=X    删除壁纸
 *
 * 只在「远程导入」页面打开时监听，退出页面即关闭，避免长期开端口。
 */
public class RemoteImportServer {

    private static final String TAG = "DSH_LAUNCHER";
    private static final int MAX_UPLOAD_BYTES = 40 * 1024 * 1024;
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int THUMB_W = 400;
    private static final int THUMB_CACHE_MAX = 48;

    /** 由 LauncherActivity 实现；回调可能来自任意线程 */
    public interface Host {
        /** 当前壁纸文件名列表（按管理页顺序） */
        List<String> wallpaperNames();

        /** 当前正在显示的壁纸文件名，可能为 null */
        String currentWallpaper();

        /** backgrounds_4k 目录 */
        File wallpaperDir();

        /** 把 length 字节的流保存为壁纸，返回最终文件名；失败返回 null */
        String saveWallpaper(String fileName, InputStream in, long length) throws IOException;

        boolean applyWallpaper(String name);

        boolean deleteWallpaper(String name);

        void onRemoteEvent(String message);
    }

    private final Host host;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, byte[]> thumbCache =
            Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > THUMB_CACHE_MAX;
                }
            });

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int port = -1;

    public RemoteImportServer(Host host) {
        this.host = host;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return port;
    }

    /** 依次尝试 preferredPort..preferredPort+9，全部失败返回 false */
    public boolean start(int preferredPort) {
        if (running.get()) {
            return true;
        }
        for (int p = preferredPort; p < preferredPort + 10; p++) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress("0.0.0.0", p));
            } catch (IOException e) {
                closeQuietly(ss);
                continue;
            }
            serverSocket = ss;
            port = p;
            running.set(true);
            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop();
                }
            }, "wallpaper-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "remote import server listening on " + p);
            return true;
        }
        return false;
    }

    public void stop() {
        running.set(false);
        closeQuietly(serverSocket);
        serverSocket = null;
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            t.interrupt();
        }
        thumbCache.clear();
        Log.i(TAG, "remote import server stopped");
    }

    // ---------- 连接循环 ----------

    private void acceptLoop() {
        while (running.get()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (running.get()) {
                    Log.w(TAG, "accept failed", e);
                }
                return;
            }
            final Socket s = socket;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        s.setSoTimeout(30000);
                        handle(s);
                    } catch (Throwable t) {
                        Log.w(TAG, "request failed", t);
                    } finally {
                        closeQuietly(s);
                    }
                }
            }, "wallpaper-http-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream rawIn = new BufferedInputStream(socket.getInputStream(), 32 * 1024);
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 32 * 1024);

        byte[] headerBytes = readHeader(rawIn);
        if (headerBytes == null) {
            return;
        }
        String header = new String(headerBytes, "UTF-8");
        String[] lines = header.split("\r\n");
        if (lines.length == 0) {
            return;
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            writeJson(out, 400, "{\"ok\":false,\"error\":\"bad request\"}");
            return;
        }
        String method = requestLine[0].toUpperCase();
        String target = requestLine[1];

        long contentLength = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = lines[i].substring(0, colon).trim().toLowerCase();
            String value = lines[i].substring(colon + 1).trim();
            if ("content-length".equals(key)) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                }
            } else if ("transfer-encoding".equals(key) && value.toLowerCase().contains("chunked")) {
                chunked = true;
            }
        }

        String path = target;
        String query = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }

        Log.i(TAG, "remote " + method + " " + path + (query == null ? "" : "?" + query));

        if ("GET".equals(method) && "/".equals(path)) {
            servePage(out);
        } else if ("GET".equals(method) && "/api/list".equals(path)) {
            serveList(out);
        } else if ("GET".equals(method) && "/api/thumb".equals(path)) {
            serveThumb(out, param(query, "name"));
        } else if ("POST".equals(method) && "/api/upload".equals(path)) {
            serveUpload(socket, rawIn, out, param(query, "name"), contentLength, chunked);
        } else if ("POST".equals(method) && "/api/apply".equals(path)) {
            serveApply(out, param(query, "name"));
        } else if ("POST".equals(method) && "/api/delete".equals(path)) {
            serveDelete(out, param(query, "name"));
        } else {
            writeJson(out, 404, "{\"ok\":false,\"error\":\"not found\"}");
        }
        out.flush();
    }

    /** 读到空行为止，返回头部原始字节（不含结束的 CRLFCRLF） */
    private byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == '\r' && (matched == 0 || matched == 2)) {
                matched++;
            } else if (b == '\n' && (matched == 1 || matched == 3)) {
                matched++;
                if (matched == 4) {
                    byte[] all = buf.toByteArray();
                    byte[] trimmed = new byte[all.length - 4];
                    System.arraycopy(all, 0, trimmed, 0, trimmed.length);
                    return trimmed;
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
            if (buf.size() > MAX_HEADER_BYTES) {
                return null;
            }
        }
        return null;
    }

    // ---------- 各路由实现 ----------

    /** 由 Activity 注入，用来读 assets/www/upload.html */
    public interface AssetOpener {
        InputStream open(String assetName) throws IOException;
    }

    private AssetOpener assetOpener;

    public void setAssetOpener(AssetOpener opener) {
        this.assetOpener = opener;
    }

    private void servePage(OutputStream out) throws IOException {
        InputStream in = null;
        try {
            in = assetOpener == null ? null : assetOpener.open("www/upload.html");
            if (in == null) {
                writeJson(out, 500, "{\"ok\":false,\"error\":\"upload.html missing\"}");
                return;
            }
            byte[] body = readAll(in);
            writeResponse(out, 200, "text/html; charset=utf-8", body);
            host.onRemoteEvent("浏览器已连接 " + host.wallpaperNames().size() + " 张壁纸");
        } finally {
            closeQuietly(in);
        }
    }

    private void serveList(OutputStream out) throws IOException {
        List<String> names = host.wallpaperNames();
        String current = host.currentWallpaper();
        File dir = host.wallpaperDir();
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"ok\":true,\"current\":").append(jsonString(current)).append(",\"items\":[");
        boolean first = true;
        for (String name : names) {
            if (name == null) {
                continue;
            }
            File f = new File(dir, name);
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append("{\"name\":").append(jsonString(name))
                    .append(",\"size\":").append(f.length())
                    .append(",\"mtime\":").append(f.lastModified())
                    .append(",\"current\":").append(name.equals(current))
                    .append('}');
        }
        sb.append("]}");
        writeResponse(out, 200, "application/json; charset=utf-8", sb.toString().getBytes("UTF-8"));
    }

    private void serveThumb(OutputStream out, String name) throws IOException {
        if (!isSafeName(name)) {
            writeJson(out, 400, "{\"ok\":false,\"error\":\"bad name\"}");
            return;
        }
        File file = new File(host.wallpaperDir(), name);
        if (!file.isFile()) {
            writeJson(out, 404, "{\"ok\":false,\"error\":\"not found\"}");
            return;
        }
        String key = name + "@" + file.lastModified() + "#" + file.length();
        byte[] cached = thumbCache.get(key);
        if (cached == null) {
            cached = buildThumb(file);
            if (cached == null) {
                writeJson(out, 415, "{\"ok\":false,\"error\":\"decode failed\"}");
                return;
            }
            thumbCache.put(key, cached);
        }
        writeResponse(out, 200, "image/jpeg", cached);
    }

    private byte[] buildThumb(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= THUMB_W) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) {
                return null;
            }
            int w = THUMB_W;
            int h = Math.max(1, Math.round(bmp.getHeight() * (THUMB_W / (float) bmp.getWidth())));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
            if (scaled != bmp) {
                bmp.recycle();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            scaled.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            Log.w(TAG, "thumbnail failed for " + file.getName(), t);
            return null;
        }
    }

    private void serveUpload(Socket socket, InputStream in, OutputStream out,
                            String name, long contentLength, boolean chunked) throws IOException {
        if (!isSafeName(name)) {
            writeJson(out, 400, "{\"ok\":false,\"error\":\"bad name\"}");
            return;
        }
        if (chunked || contentLength <= 0) {
            writeJson(out, 411, "{\"ok\":false,\"error\":\"content-length required\"}");
            return;
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            writeJson(out, 413, "{\"ok\":false,\"error\":\"too large\"}");
            return;
        }
        String saved = host.saveWallpaper(name, new LimitedInputStream(in, contentLength), contentLength);
        if (saved == null) {
            writeJson(out, 415, "{\"ok\":false,\"error\":\"not a valid image\"}");
            return;
        }
        Log.i(TAG, "remote upload saved " + saved);
        host.onRemoteEvent("已上传 " + saved);
        writeJson(out, 200, "{\"ok\":true,\"name\":" + jsonString(saved) + "}");
    }

    private void serveApply(OutputStream out, String name) throws IOException {
        if (!isSafeName(name)) {
            writeJson(out, 400, "{\"ok\":false,\"error\":\"bad name\"}");
            return;
        }
        boolean ok = host.applyWallpaper(name);
        if (ok) {
            host.onRemoteEvent("已切换壁纸 " + name);
        }
        writeJson(out, ok ? 200 : 404, ok
                ? "{\"ok\":true}"
                : "{\"ok\":false,\"error\":\"not found\"}");
    }

    private void serveDelete(OutputStream out, String name) throws IOException {
        if (!isSafeName(name)) {
            writeJson(out, 400, "{\"ok\":false,\"error\":\"bad name\"}");
            return;
        }
        boolean ok = host.deleteWallpaper(name);
        if (ok) {
            host.onRemoteEvent("已删除 " + name);
            thumbCache.clear();
        }
        writeJson(out, ok ? 200 : 404, ok
                ? "{\"ok\":true}"
                : "{\"ok\":false,\"error\":\"delete failed\"}");
    }

    // ---------- 工具 ----------

    private static String param(String query, String key) {
        if (query == null) {
            return null;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (!key.equals(k)) {
                continue;
            }
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                return URLDecoder.decode(v, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                return v;
            }
        }
        return null;
    }

    /** 只允许纯文件名，禁止路径穿越 */
    static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > 200) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private void writeJson(OutputStream out, int status, String json) throws IOException {
        writeResponse(out, status, "application/json; charset=utf-8", json.getBytes("UTF-8"));
    }

    private void writeResponse(OutputStream out, int status, String contentType, byte[] body)
            throws IOException {
        StringBuilder head = new StringBuilder(192);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append("Content-Type: ").append(contentType).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 411:
                return "Length Required";
            case 413:
                return "Payload Too Large";
            case 415:
                return "Unsupported Media Type";
            case 500:
                return "Internal Server Error";
            default:
                return "OK";
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(ServerSocket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 包装上传流，保证正好读 length 字节，避免把后续连接数据读进来 */
    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int b = in.read();
            if (b >= 0) {
                remaining--;
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int want = (int) Math.min(len, remaining);
            int n = in.read(buf, off, want);
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
