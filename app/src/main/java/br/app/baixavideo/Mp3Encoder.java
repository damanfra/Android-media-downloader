package br.app.baixavideo;

/** Ponte JNI para o LAME (libmp3lame). */
final class Mp3Encoder {
    static {
        System.loadLibrary("mp3enc");
    }

    private Mp3Encoder() {}

    static native long init(int sampleRate, int channels, int kbps);

    /** pcm intercalado (16 bits); frames = amostras por canal. Retorna bytes gravados em out. */
    static native int encode(long handle, short[] pcm, int frames, int channels, byte[] out);

    static native int flush(long handle, byte[] out);

    static native void close(long handle);
}
