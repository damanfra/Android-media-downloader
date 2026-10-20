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

/** Baixa, junta trilhas com o MediaMuxer nativo (ou converte para MP3) e salva na pasta Download. */
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

    static Result run(Context ctx, MediaInfo mi, MediaInfo.Option o, boolean audioOnly, String fileName,
                      AtomicBoolean cancel, Listener l) throws Exception {
        File dir = ctx.getCacheDir();
        File v = new File(dir, "v.tmp"), a = new File(dir, "a.tmp"), out = new File(dir, "out.tmp");
        v.delete();
        a.delete();
        out.delete();
        String base = sanitize(fileName == null || fileName.trim().isEmpty() ? mi.title : fileName);
        try {
            if (audioOnly) {
                l.onStage("Baixando…");
                Net.download(o.audioUrl, o.audioLen, mi.headers, a, cancel, l, 0, o.audioLen);
                l.onStage("Convertendo para MP3…");
                toMp3(a, out, cancel, l);
                return save(ctx, out, base + ".mp3", "audio/mpeg");
            }
            if (o.audioUrl == null) {
                l.onStage("Baixando vídeo…");
                Net.download(o.videoUrl, o.videoLen, mi.headers, v, cancel, l, 0, o.videoLen);
                l.onStage("Finalizando…");
                // remuxa (sem recodificar) para o MP4 levar a data de agora, não a da postagem
                mux(out, new File[]{v, v}, new String[]{"video/", "audio/?"});
                return save(ctx, out, base + ".mp4", "video/mp4");
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

    /** Decodifica a trilha de áudio com o MediaCodec do Android e codifica em MP3 (LAME, 192 kbps). */
    static void toMp3(File in, File out, AtomicBoolean cancel, Listener l) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        long enc = 0;
        try (OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(out), 256 * 1024)) {
            ex.setDataSource(in.getPath());
            MediaFormat fmt = null;
            for (int j = 0; j < ex.getTrackCount(); j++) {
                MediaFormat f = ex.getTrackFormat(j);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(j);
                    fmt = f;
                    break;
                }
            }
            if (fmt == null) throw new IOException("Esse vídeo não tem áudio.");
            long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            short[] pcm = new short[0];
            byte[] mp3 = new byte[0];
            boolean inDone = false;
            int encCh = 0;
            while (true) {
                if (cancel.get()) throw new Net.CancelledException();
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int size = b == null ? -1 : ex.readSampleData(b, 0);
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        if (enc == 0) {
                            encCh = Math.min(ch, 2);
                            enc = Mp3Encoder.init(rate, encCh, 192);
                            if (enc == 0) throw new IOException("Não consegui iniciar o codificador MP3.");
                        }
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        java.nio.ShortBuffer sb = ob.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                        int count = sb.remaining();
                        int frames = count / ch;
                        if (pcm.length < count) pcm = new short[count];
                        sb.get(pcm, 0, count);
                        if (ch > 2) { // reduz para estéreo usando os dois primeiros canais
                            for (int f = 0; f < frames; f++) {
                                pcm[2 * f] = pcm[f * ch];
                                pcm[2 * f + 1] = pcm[f * ch + 1];
                            }
                        }
                        int need = frames * 5 / 4 + 7200;
                        if (mp3.length < need) mp3 = new byte[need];
                        int n = Mp3Encoder.encode(enc, pcm, frames, encCh, mp3);
                        if (n < 0) throw new IOException("Erro no codificador MP3 (" + n + ").");
                        os.write(mp3, 0, n);
                        if (durationUs > 0) l.onProgress(Math.min(info.presentationTimeUs, durationUs), durationUs);
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            if (enc == 0) throw new IOException("O áudio veio vazio.");
            if (mp3.length < 7200) mp3 = new byte[7200];
            int n = Mp3Encoder.flush(enc, mp3);
            if (n > 0) os.write(mp3, 0, n);
        } finally {
            if (enc != 0) Mp3Encoder.close(enc);
            if (dec != null) {
                try {
                    dec.stop();
                } catch (Exception ignored) {
                }
                dec.release();
            }
            ex.release();
        }
    }

    /** Copia, sem recodificar, a primeira trilha de cada arquivo cujo MIME começa com o prefixo dado. */
    static void mux(File out, File[] files, String[] prefixes) throws IOException {
        int n = files.length;
        boolean[] optional = new boolean[n];
        prefixes = prefixes.clone();
        for (int i = 0; i < n; i++) {
            if (prefixes[i].endsWith("?")) {
                optional[i] = true;
                prefixes[i] = prefixes[i].substring(0, prefixes[i].length() - 1);
            }
        }
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
                if (track < 0 && optional[i]) {
                    ex[i].release();
                    ex[i] = null;
                    continue;
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
                    if (done[i] || ex[i] == null) continue;
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
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        cv.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
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
        cv.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
        cr.update(uri, cv, null, null);
        return new Result(uri, mime, name);
    }

    static String sanitize(String s) {
        String t = s == null ? "" : s.trim().replaceAll("(?i)\\.(mp3|mp4|m4a)$", "").replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? "video_" + System.currentTimeMillis() : t;
    }
}
