package br.app.baixavideo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** O que foi encontrado no link: título, opções de vídeo e a fonte do áudio. */
final class MediaInfo {
    String title;
    String source;
    final List<Option> video = new ArrayList<>();
    Option audio;
    /** Cabeçalhos que o servidor de mídia espera (ex.: User-Agent do cliente do YouTube). */
    final Map<String, String> headers = new HashMap<>();

    static final class Option {
        final String label;
        /** Vídeo (ou arquivo único com vídeo+áudio). Nulo na opção de áudio. */
        final String videoUrl;
        final long videoLen;
        /** Trilha de áudio separada para juntar, ou o arquivo de onde extrair o áudio. */
        final String audioUrl;
        final long audioLen;

        Option(String label, String videoUrl, long videoLen, String audioUrl, long audioLen) {
            this.label = label;
            this.videoUrl = videoUrl;
            this.videoLen = videoLen;
            this.audioUrl = audioUrl;
            this.audioLen = audioLen;
        }
    }

    static String mb(long bytes) {
        if (bytes <= 0) return "";
        return String.format(java.util.Locale.US, " · ~%.1f MB", bytes / 1048576.0);
    }
}
