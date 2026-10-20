package br.app.baixavideo;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** HTTP simples, sem dependências. */
final class Net {
    static final String BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";
    private static final long CHUNK = 9L * 1024 * 1024; // pedaços de 9 MB (evita estrangulamento do YouTube)

    interface Progress {
        void onProgress(long done, long total);
    }

    private Net() {}

    private static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", BROWSER_UA);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        }
        return c;
    }

    static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, headers);
        try {
            return readBody(c);
        } finally {
            c.disconnect();
        }
    }

    static String postJson(String url, String json, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, headers);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            return readBody(c);
        } finally {
            c.disconnect();
        }
    }

    private static String readBody(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = "";
        if (in != null) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            in.close();
            body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
        if (code / 100 != 2) throw new IOException("HTTP " + code);
        return body;
    }

    /**
     * Baixa {@code url} para {@code out}. Se o tamanho é conhecido, baixa em pedaços com Range
     * e retoma automaticamente em caso de falha de rede.
     */
    static void download(String url, long len, Map<String, String> headers, File out, AtomicBoolean cancel,
                         Progress p, long offset, long total) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long done = 0;
        int failures = 0;
        try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 256 * 1024)) {
            if (len <= 0) {
                HttpURLConnection c = open(url, headers);
                try {
                    int code = c.getResponseCode();
                    if (code / 100 != 2) throw new IOException("HTTP " + code + " ao baixar");
                    long cl = c.getContentLengthLong();
                    if (total <= 0 && cl > 0) total = cl;
                    InputStream in = c.getInputStream();
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (cancel.get()) throw new CancelledException();
                        os.write(buf, 0, r);
                        done += r;
                        p.onProgress(offset + done, total);
                    }
                    in.close();
                } finally {
                    c.disconnect();
                }
                return;
            }
            while (done < len) {
                long end = Math.min(len, done + CHUNK) - 1;
                HttpURLConnection c = open(url, headers);
                c.setRequestProperty("Range", "bytes=" + done + "-" + end);
                try {
                    int code = c.getResponseCode();
                    boolean whole = code == 200;
                    if (code != 206 && !(whole && done == 0)) throw new IOException("HTTP " + code + " ao baixar");
                    InputStream in = c.getInputStream();
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        if (cancel.get()) throw new CancelledException();
                        os.write(buf, 0, r);
                        done += r;
                        p.onProgress(offset + done, total);
                    }
                    in.close();
                    if (whole) return;
                    failures = 0;
                } catch (CancelledException e) {
                    throw e;
                } catch (IOException e) {
                    if (++failures > 4) throw e; // retoma do byte onde parou
                } finally {
                    c.disconnect();
                }
            }
        }
    }

    static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelado");
        }
    }
}
