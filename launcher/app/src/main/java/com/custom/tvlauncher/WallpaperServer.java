package com.custom.tvlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight, zero-dependency local Wi-Fi HTTP server.
 * Enables uploading custom wallpapers from any smartphone, tablet, or PC browser.
 */
public class WallpaperServer {
    public static final int PORT = 7070;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final File wallpaperDir;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running;
    private Listener listener;

    public interface Listener {
        void onWallpaperUploaded(File file, String name);
        void onWallpaperDeleted(String name);
        void onWallpaperSelected(File file);
    }

    public WallpaperServer(Context context) {
        this.context = context.getApplicationContext();
        this.wallpaperDir = new File(this.context.getFilesDir(), "custom_wallpapers");
        if (!wallpaperDir.exists()) {
            wallpaperDir.mkdirs();
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running) {
                    try {
                        Socket socket = serverSocket.accept();
                        new Thread(() -> handleClient(socket)).start();
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                running = false;
            }
        }, "WallpaperServerThread");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
        if (serverThread != null) {
            serverThread.interrupt();
            serverThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public static String getIpAddress(Context context) {
        try {
            // Check Wi-Fi manager first
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return Formatter.formatIpAddress(ip);
                }
            }
            // Check network interfaces
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.100.70";
    }

    public List<File> getCustomWallpapers() {
        List<File> list = new ArrayList<>();
        if (wallpaperDir.exists()) {
            File[] files = wallpaperDir.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
            });
            if (files != null) {
                for (File f : files) list.add(f);
                list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
        }
        return list;
    }

    private void handleClient(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            // Read headers
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            int consecutiveLf = 0;
            while ((b = in.read()) != -1) {
                headerBuffer.write(b);
                if (b == '\n') consecutiveLf++;
                else if (b != '\r') consecutiveLf = 0;
                if (consecutiveLf == 2) break; // Double CRLF found
            }

            String headerText = new String(headerBuffer.toByteArray(), StandardCharsets.ISO_8859_1);
            if (headerText.isEmpty()) {
                socket.close();
                return;
            }

            String[] lines = headerText.split("\r\n");
            String requestLine = lines[0];
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }
            String method = parts[0].toUpperCase();
            String path = parts[1];

            int contentLength = 0;
            String contentType = "";
            for (String line : lines) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                } else if (lower.startsWith("content-type:")) {
                    contentType = line.substring(13).trim();
                }
            }

            if ("GET".equals(method)) {
                if ("/".equals(path) || path.startsWith("/index")) {
                    serveHtml(out);
                } else if (path.startsWith("/thumb/")) {
                    serveThumbnail(path.substring(7), out);
                } else if ("/list".equals(path)) {
                    serveList(out);
                } else {
                    send404(out);
                }
            } else if ("POST".equals(method)) {
                if ("/upload".equals(path)) {
                    handleUpload(in, contentLength, contentType, out);
                } else if ("/apply".equals(path)) {
                    handleApply(in, contentLength, out);
                } else if ("/delete".equals(path)) {
                    handleDelete(in, contentLength, out);
                } else {
                    send404(out);
                }
            } else {
                sendResponse(out, "405 Method Not Allowed", "text/plain", "Method Not Allowed".getBytes());
            }

            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void serveHtml(OutputStream out) throws IOException {
        String html = "<!DOCTYPE html>\n" +
                "<html lang=\"en\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>Skyworth TV Wallpaper Uploader</title>\n" +
                "  <style>\n" +
                "    * { box-sizing: border-box; margin: 0; padding: 0; }\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "           background: #0b111c; color: #f2f4f8; min-height: 100vh; padding: 24px; }\n" +
                "    .container { max-width: 600px; margin: 0 auto; }\n" +
                "    header { text-align: center; margin-bottom: 28px; }\n" +
                "    h1 { font-size: 24px; font-weight: 600; color: #ffffff; letter-spacing: -0.5px; }\n" +
                "    p.sub { color: #8e9bb0; font-size: 14px; margin-top: 6px; }\n" +
                "    .card { background: rgba(27, 36, 52, 0.7); backdrop-filter: blur(16px);\n" +
                "            border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 18px;\n" +
                "            padding: 24px; margin-bottom: 24px; box-shadow: 0 10px 30px rgba(0,0,0,0.4); }\n" +
                "    .drop-zone { border: 2px dashed rgba(255, 255, 255, 0.25); border-radius: 14px;\n" +
                "                padding: 32px 16px; text-align: center; cursor: pointer;\n" +
                "                transition: all 0.2s ease; background: rgba(14, 21, 33, 0.4); }\n" +
                "    .drop-zone:hover, .drop-zone.dragover { border-color: #5cbcc9; background: rgba(92, 188, 201, 0.1); }\n" +
                "    .drop-icon { font-size: 40px; margin-bottom: 10px; }\n" +
                "    .drop-text { font-size: 15px; font-weight: 500; color: #e1e7f0; }\n" +
                "    .drop-hint { font-size: 12px; color: #7f8ca3; margin-top: 6px; }\n" +
                "    input[type=file] { display: none; }\n" +
                "    #previewContainer { display: none; margin-top: 18px; text-align: center; }\n" +
                "    #previewImg { max-width: 100%; max-height: 220px; border-radius: 10px; object-fit: cover;\n" +
                "                  border: 1px solid rgba(255,255,255,0.2); box-shadow: 0 4px 12px rgba(0,0,0,0.5); }\n" +
                "    button.btn { width: 100%; background: #ffffff; color: #0d1420; border: none;\n" +
                "                border-radius: 12px; padding: 14px; font-size: 15px; font-weight: 600;\n" +
                "                cursor: pointer; margin-top: 18px; transition: transform 0.15s, opacity 0.15s; }\n" +
                "    button.btn:active { transform: scale(0.98); opacity: 0.9; }\n" +
                "    button.btn:disabled { opacity: 0.4; cursor: not-allowed; }\n" +
                "    #statusMsg { margin-top: 14px; font-size: 14px; text-align: center; font-weight: 500; display: none; }\n" +
                "    .status-success { color: #5cebb5; }\n" +
                "    .status-error { color: #ff6b7b; }\n" +
                "    h2 { font-size: 18px; font-weight: 600; margin-bottom: 14px; color: #d6dfea; }\n" +
                "    .gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(130px, 1fr)); gap: 12px; }\n" +
                "    .gallery-item { position: relative; border-radius: 10px; overflow: hidden;\n" +
                "                   border: 1px solid rgba(255,255,255,0.1); aspect-ratio: 16/9;\n" +
                "                   background: #151f2e; }\n" +
                "    .gallery-item img { width: 100%; height: 100%; object-fit: cover; display: block; }\n" +
                "    .gallery-overlay { position: absolute; inset: 0; background: rgba(0,0,0,0.6);\n" +
                "                       display: flex; gap: 8px; align-items: center; justify-content: center;\n" +
                "                       opacity: 0; transition: opacity 0.2s; }\n" +
                "    .gallery-item:hover .gallery-overlay { opacity: 1; }\n" +
                "    .gallery-btn { background: rgba(255,255,255,0.9); border: none; border-radius: 6px;\n" +
                "                   padding: 6px 10px; font-size: 11px; font-weight: 600; cursor: pointer; color: #111; }\n" +
                "    .gallery-btn.del { background: #ff4757; color: white; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"container\">\n" +
                "    <header>\n" +
                "      <h1>Skyworth TV Wallpaper</h1>\n" +
                "      <p class=\"sub\">Upload and instantly apply wallpapers to your TV</p>\n" +
                "    </header>\n" +
                "    <div class=\"card\">\n" +
                "      <div class=\"drop-zone\" id=\"dropZone\">\n" +
                "        <div class=\"drop-icon\">&#128247;</div>\n" +
                "        <div class=\"drop-text\">Choose photo or take picture</div>\n" +
                "        <div class=\"drop-hint\">JPG, PNG or WEBP (16:9 recommended)</div>\n" +
                "        <input type=\"file\" id=\"fileInput\" accept=\"image/*\">\n" +
                "      </div>\n" +
                "      <div id=\"previewContainer\">\n" +
                "        <img id=\"previewImg\" alt=\"Preview\">\n" +
                "        <button class=\"btn\" id=\"uploadBtn\">Apply to TV</button>\n" +
                "      </div>\n" +
                "      <div id=\"statusMsg\"></div>\n" +
                "    </div>\n" +
                "    <div class=\"card\" id=\"galleryCard\">\n" +
                "      <h2>Uploaded Wallpapers</h2>\n" +
                "      <div class=\"gallery\" id=\"galleryGrid\"></div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <script>\n" +
                "    const dropZone = document.getElementById('dropZone');\n" +
                "    const fileInput = document.getElementById('fileInput');\n" +
                "    const previewContainer = document.getElementById('previewContainer');\n" +
                "    const previewImg = document.getElementById('previewImg');\n" +
                "    const uploadBtn = document.getElementById('uploadBtn');\n" +
                "    const statusMsg = document.getElementById('statusMsg');\n" +
                "    const galleryGrid = document.getElementById('galleryGrid');\n" +
                "    let selectedFile = null;\n" +
                "    dropZone.addEventListener('click', () => fileInput.click());\n" +
                "    fileInput.addEventListener('change', (e) => {\n" +
                "      if (e.target.files.length) handleFile(e.target.files[0]);\n" +
                "    });\n" +
                "    ['dragenter', 'dragover'].forEach(eName => {\n" +
                "      dropZone.addEventListener(eName, (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });\n" +
                "    });\n" +
                "    ['dragleave', 'drop'].forEach(eName => {\n" +
                "      dropZone.addEventListener(eName, (e) => { e.preventDefault(); dropZone.classList.remove('dragover'); });\n" +
                "    });\n" +
                "    dropZone.addEventListener('drop', (e) => {\n" +
                "      if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]);\n" +
                "    });\n" +
                "    function handleFile(file) {\n" +
                "      selectedFile = file;\n" +
                "      const reader = new FileReader();\n" +
                "      reader.onload = (e) => {\n" +
                "        previewImg.src = e.target.result;\n" +
                "        previewContainer.style.display = 'block';\n" +
                "        statusMsg.style.display = 'none';\n" +
                "      };\n" +
                "      reader.readAsDataURL(file);\n" +
                "    }\n" +
                "    uploadBtn.addEventListener('click', () => {\n" +
                "      if (!selectedFile) return;\n" +
                "      uploadBtn.disabled = true;\n" +
                "      uploadBtn.textContent = 'Uploading & applying...';\n" +
                "      statusMsg.style.display = 'none';\n" +
                "      fetch('/upload', {\n" +
                "        method: 'POST',\n" +
                "        headers: { 'Content-Type': selectedFile.type || 'application/octet-stream', 'X-Filename': encodeURIComponent(selectedFile.name) },\n" +
                "        body: selectedFile\n" +
                "      })\n" +
                "      .then(res => res.json())\n" +
                "      .then(data => {\n" +
                "        uploadBtn.disabled = false;\n" +
                "        uploadBtn.textContent = 'Apply to TV';\n" +
                "        statusMsg.className = 'status-success';\n" +
                "        statusMsg.textContent = '✓ Wallpaper applied on your TV screen!';\n" +
                "        statusMsg.style.display = 'block';\n" +
                "        loadGallery();\n" +
                "      })\n" +
                "      .catch(err => {\n" +
                "        uploadBtn.disabled = false;\n" +
                "        uploadBtn.textContent = 'Apply to TV';\n" +
                "        statusMsg.className = 'status-error';\n" +
                "        statusMsg.textContent = 'Failed to upload: ' + err.message;\n" +
                "        statusMsg.style.display = 'block';\n" +
                "      });\n" +
                "    });\n" +
                "    function loadGallery() {\n" +
                "      fetch('/list')\n" +
                "        .then(r => r.json())\n" +
                "        .then(list => {\n" +
                "          galleryGrid.innerHTML = '';\n" +
                "          if (list.length === 0) {\n" +
                "            galleryGrid.innerHTML = '<p style=\"grid-column: 1/-1; color: #7f8ca3; font-size: 13px; text-align: center;\">No custom wallpapers uploaded yet.</p>';\n" +
                "            return;\n" +
                "          }\n" +
                "          list.forEach(name => {\n" +
                "            const div = document.createElement('div');\n" +
                "            div.className = 'gallery-item';\n" +
                "            div.innerHTML = `<img src=\"/thumb/${encodeURIComponent(name)}\" alt=\"${name}\">\n" +
                "              <div class=\"gallery-overlay\">\n" +
                "                <button class=\"gallery-btn\" onclick=\"applyWall('${name}')\">Apply</button>\n" +
                "                <button class=\"gallery-btn del\" onclick=\"deleteWall('${name}')\">Delete</button>\n" +
                "              </div>`;\n" +
                "            galleryGrid.appendChild(div);\n" +
                "          });\n" +
                "        });\n" +
                "    }\n" +
                "    window.applyWall = (name) => {\n" +
                "      fetch('/apply', { method: 'POST', body: name })\n" +
                "        .then(() => {\n" +
                "          statusMsg.className = 'status-success';\n" +
                "          statusMsg.textContent = '✓ Applied: ' + name;\n" +
                "          statusMsg.style.display = 'block';\n" +
                "        });\n" +
                "    };\n" +
                "    window.deleteWall = (name) => {\n" +
                "      if (!confirm('Delete this wallpaper?')) return;\n" +
                "      fetch('/delete', { method: 'POST', body: name })\n" +
                "        .then(() => loadGallery());\n" +
                "    };\n" +
                "    loadGallery();\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";

        sendResponse(out, "200 OK", "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void serveList(OutputStream out) throws IOException {
        StringBuilder json = new StringBuilder("[");
        List<File> files = getCustomWallpapers();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(files.get(i).getName()).append("\"");
        }
        json.append("]");
        sendResponse(out, "200 OK", "application/json", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void serveThumbnail(String filename, OutputStream out) throws IOException {
        File file = new File(wallpaperDir, filename);
        if (!file.exists()) {
            send404(out);
            return;
        }
        FileInputStream fis = new FileInputStream(file);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = fis.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        fis.close();
        sendResponse(out, "200 OK", "image/jpeg", buffer.toByteArray());
    }

    private void handleUpload(InputStream in, int contentLength, String contentType, OutputStream out) throws IOException {
        if (contentLength <= 0 || contentLength > 30 * 1024 * 1024) {
            sendResponse(out, "400 Bad Request", "application/json", "{\"error\":\"File too large or invalid\"}".getBytes());
            return;
        }

        byte[] body = new byte[contentLength];
        int totalRead = 0;
        while (totalRead < contentLength) {
            int read = in.read(body, totalRead, contentLength - totalRead);
            if (read == -1) break;
            totalRead += read;
        }

        byte[] imageBytes = body;
        // Check if multipart form
        if (contentType.toLowerCase().contains("multipart/form-data")) {
            String boundary = "";
            for (String part : contentType.split(";")) {
                if (part.trim().toLowerCase().startsWith("boundary=")) {
                    boundary = "--" + part.trim().substring(9);
                }
            }
            if (!boundary.isEmpty()) {
                imageBytes = extractMultipartPayload(body, boundary.getBytes(StandardCharsets.ISO_8859_1));
            }
        }

        // Verify valid image by decoding
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, opts);
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            sendResponse(out, "400 Bad Request", "application/json", "{\"error\":\"Invalid image data\"}".getBytes());
            return;
        }

        String filename = "custom_" + System.currentTimeMillis() + ".jpg";
        File targetFile = new File(wallpaperDir, filename);

        // Decode and scale if larger than 1080p to conserve memory
        BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
        decodeOpts.inSampleSize = Math.max(1, Math.max(opts.outWidth / 1920, opts.outHeight / 1080));
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, decodeOpts);
        if (bitmap != null) {
            FileOutputStream fos = new FileOutputStream(targetFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.close();
            bitmap.recycle();
        } else {
            FileOutputStream fos = new FileOutputStream(targetFile);
            fos.write(imageBytes);
            fos.close();
        }

        handler.post(() -> {
            if (listener != null) {
                listener.onWallpaperUploaded(targetFile, targetFile.getName());
            }
        });

        sendResponse(out, "200 OK", "application/json",
                ("{\"success\":true,\"name\":\"" + filename + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private void handleApply(InputStream in, int contentLength, OutputStream out) throws IOException {
        byte[] body = new byte[Math.max(0, contentLength)];
        int total = 0;
        while (total < contentLength) {
            int read = in.read(body, total, contentLength - total);
            if (read == -1) break;
            total += read;
        }
        String filename = new String(body, StandardCharsets.UTF_8).trim();
        File file = new File(wallpaperDir, filename);
        if (file.exists()) {
            handler.post(() -> {
                if (listener != null) listener.onWallpaperSelected(file);
            });
            sendResponse(out, "200 OK", "application/json", "{\"success\":true}".getBytes());
        } else {
            send404(out);
        }
    }

    private void handleDelete(InputStream in, int contentLength, OutputStream out) throws IOException {
        byte[] body = new byte[Math.max(0, contentLength)];
        int total = 0;
        while (total < contentLength) {
            int read = in.read(body, total, contentLength - total);
            if (read == -1) break;
            total += read;
        }
        String filename = new String(body, StandardCharsets.UTF_8).trim();
        File file = new File(wallpaperDir, filename);
        if (file.exists()) {
            file.delete();
            handler.post(() -> {
                if (listener != null) listener.onWallpaperDeleted(filename);
            });
            sendResponse(out, "200 OK", "application/json", "{\"success\":true}".getBytes());
        } else {
            send404(out);
        }
    }

    private byte[] extractMultipartPayload(byte[] data, byte[] boundary) {
        int start = -1;
        int end = -1;
        // Find boundary
        for (int i = 0; i <= data.length - boundary.length; i++) {
            boolean match = true;
            for (int j = 0; j < boundary.length; j++) {
                if (data[i + j] != boundary[j]) { match = false; break; }
            }
            if (match) {
                if (start == -1) {
                    // Find header delimiter \r\n\r\n after boundary
                    for (int k = i + boundary.length; k < data.length - 3; k++) {
                        if (data[k] == '\r' && data[k+1] == '\n' && data[k+2] == '\r' && data[k+3] == '\n') {
                            start = k + 4;
                            break;
                        }
                    }
                    i = start > 0 ? start : i + boundary.length;
                } else {
                    end = i - 2; // Before \r\n
                    break;
                }
            }
        }
        if (start > 0 && end > start) {
            byte[] res = new byte[end - start];
            System.arraycopy(data, start, res, 0, res.length);
            return res;
        }
        return data;
    }

    private void sendResponse(OutputStream out, String status, String mime, byte[] body) throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: " + mime + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private void send404(OutputStream out) throws IOException {
        sendResponse(out, "404 Not Found", "text/plain", "Not Found".getBytes());
    }
}
