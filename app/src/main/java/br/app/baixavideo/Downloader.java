package br.app.baixavideo;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Baixa, junta/extrai trilhas com o MediaMuxer nativo e salva em Download/BaixaVideo. */
final class Downloader {
    interface Listener extends Net.Progress {
        void onStage(String text);
    }

    static final class Result {
        final Uri uri;
        final String mime;
        final String name;

        Result(Uri uri, String mime, String name) {
            this.uri = uri;
            this.mime = mime;
            this.name = name;
        }
    }

    private Downloader() {}

    static Result run(Context ctx, MediaInfo mi, MediaInfo.Option o, boolean audioOnly,
                      AtomicBoolean cancel, Listener l) throws Exception {
        File dir = ctx.getCacheDir();
        File v = new File(dir, "v.tmp"), a = new File(dir, "a.tmp"), out = new File(dir, "out.tmp");
        v.delete();
        a.delete();
        out.delete();
        String base = sanitize(mi.title);
        try {
            if (audioOnly) {
                l.onStage("Baixando…");
                Net.download(o.audioUrl, o.audioLen, mi.headers, a, cancel, l, 0, o.audioLen);
                l.onStage("Extraindo o áudio…");
                mux(out, new File[]{a}, new String[]{"audio/"});
                return save(ctx, out, base + ".m4a", "audio/mp4");
            }
            if (o.audioUrl == null) {
                l.onStage("Baixando vídeo…");
                Net.download(o.videoUrl, o.videoLen, mi.headers, v, cancel, l, 0, o.videoLen);
                return save(ctx, v, base + ".mp4", "video/mp4");
            }
            long total = (o.videoLen > 0 && o.audioLen > 0) ? o.videoLen + o.audioLen : 0;
            l.onStage("Baixando vídeo…");
            Net.download(o.videoUrl, o.videoLen, mi.headers, v, cancel, l, 0, total);
            l.onStage("Baixando áudio…");
            Net.download(o.audioUrl, o.audioLen, mi.headers, a, cancel, l, o.videoLen, total);
            l.onStage("Juntando vídeo e áudio…");
            mux(out, new File[]{v, a}, new String[]{"video/", "audio/"});
            return save(ctx, out, base + ".mp4", "video/mp4");
        } finally {
            v.delete();
            a.delete();
            out.delete();
        }
    }

    /** Copia, sem recodificar, a primeira trilha de cada arquivo cujo MIME começa com o prefixo dado. */
    static void mux(File out, File[] files, String[] prefixes) throws IOException {
        int n = files.length;
        MediaExtractor[] ex = new MediaExtractor[n];
        int[] dst = new int[n];
        int maxBuf = 4 * 1024 * 1024;
        MediaMuxer m = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            for (int i = 0; i < n; i++) {
                ex[i] = new MediaExtractor();
                ex[i].setDataSource(files[i].getPath());
                int track = -1;
                for (int j = 0; j < ex[i].getTrackCount(); j++) {
                    String mime = ex[i].getTrackFormat(j).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(prefixes[i])) {
                        track = j;
                        break;
                    }
                }
                if (track < 0) {
                    throw new IOException(prefixes[i].startsWith("audio") ? "Esse vídeo não tem áudio." : "Trilha de vídeo não encontrada.");
                }
                ex[i].selectTrack(track);
                MediaFormat f = ex[i].getTrackFormat(track);
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxBuf = Math.max(maxBuf, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                if (f.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(f.getInteger(MediaFormat.KEY_ROTATION));
                dst[i] = m.addTrack(f);
            }
            m.start();
            ByteBuffer buf = ByteBuffer.allocateDirect(maxBuf);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean[] done = new boolean[n];
            while (true) {
                // intercala as trilhas por tempo
                int pick = -1;
                long best = Long.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (done[i]) continue;
                    long t = ex[i].getSampleTime();
                    if (t < 0) {
                        done[i] = true;
                        continue;
                    }
                    if (t < best) {
                        best = t;
                        pick = i;
                    }
                }
                if (pick < 0) break;
                buf.clear();
                int size = ex[pick].readSampleData(buf, 0);
                if (size < 0) {
                    done[pick] = true;
                    continue;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = ex[pick].getSampleTime();
                info.flags = (ex[pick].getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                m.writeSampleData(dst[pick], buf, info);
                ex[pick].advance();
            }
            m.stop();
        } finally {
            for (MediaExtractor e : ex) if (e != null) e.release();
            m.release();
        }
    }

    private static Result save(Context ctx, File f, String name, String mime) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BaixaVideo");
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IOException("Não consegui criar o arquivo em Downloads.");
        try (InputStream in = new FileInputStream(f); OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IOException("Não consegui gravar em Downloads.");
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) os.write(buf, 0, r);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        cv.clear();
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, cv, null, null);
        return new Result(uri, mime, name);
    }

    private static String sanitize(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? "video_" + System.currentTimeMillis() : t;
    }
}
