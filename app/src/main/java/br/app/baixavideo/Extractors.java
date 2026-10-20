package br.app.baixavideo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Descobre os arquivos de mídia por trás de um link do X ou do YouTube. */
final class Extractors {
    private Extractors() {}

    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern YT = Pattern.compile(
            "(?:youtube\\.com/(?:watch\\?(?:\\S*?&)?v=|shorts/|live/|embed/|v/)|youtu\\.be/)([A-Za-z0-9_-]{11})");
    private static final Pattern TW = Pattern.compile(
            "(?:x|twitter|fxtwitter|vxtwitter|fixupx|fixvx)\\.com/[^/\\s]+/status(?:es)?/(\\d+)");

    static String findUrl(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        return m.find() ? m.group() : null;
    }

    static MediaInfo extract(String url) throws Exception {
        Matcher y = YT.matcher(url);
        if (y.find()) return youtube(y.group(1));
        Matcher t = TW.matcher(url);
        if (t.find()) return twitter(t.group(1));
        throw new Exception("Link não reconhecido. Use um link do x.com / twitter.com ou do YouTube.");
    }

    // ------------------------------------------------------------------ X / Twitter

    private static MediaInfo twitter(String id) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        String json = null;
        Exception last = null;
        for (String token : new String[]{tweetToken(id), "a"}) {
            try {
                json = Net.get("https://cdn.syndication.twimg.com/tweet-result?id=" + id + "&lang=pt&token=" + token, h);
                if (json.trim().length() > 2) break;
            } catch (Exception e) {
                last = e;
                json = null;
            }
        }
        if (json == null) {
            throw new Exception("Não consegui ler o post (" + (last != null ? last.getMessage() : "resposta vazia")
                    + "). Posts privados, apagados ou marcados como sensíveis não funcionam.");
        }
        JSONObject o = new JSONObject(json);
        if ("TweetTombstone".equals(o.optString("__typename"))) {
            throw new Exception("O X não libera este post sem login (pode ser sensível, privado ou apagado).");
        }

        MediaInfo mi = new MediaInfo();
        mi.source = "X (Twitter)";
        JSONObject user = o.optJSONObject("user");
        String who = user != null ? "@" + user.optString("screen_name") : "post";
        String text = o.optString("text").replaceAll("https://t\\.co/\\S+", "").trim();
        if (text.length() > 70) text = text.substring(0, 70) + "…";
        mi.title = text.isEmpty() ? who + " " + id : who + ": " + text;

        List<List<JSONObject>> videos = new ArrayList<>();
        List<Boolean> hasAudio = new ArrayList<>();
        JSONArray md = o.optJSONArray("mediaDetails");
        if (md != null) {
            for (int i = 0; i < md.length(); i++) {
                JSONObject m = md.getJSONObject(i);
                JSONObject vi = m.optJSONObject("video_info");
                if (vi == null) continue;
                List<JSONObject> mp4 = mp4Variants(vi.optJSONArray("variants"));
                if (!mp4.isEmpty()) {
                    videos.add(mp4);
                    hasAudio.add(!"animated_gif".equals(m.optString("type")));
                }
            }
        }
        if (videos.isEmpty()) throw new Exception("Esse post não tem vídeo (ou o X não liberou o vídeo sem login).");

        for (int v = 0; v < videos.size(); v++) {
            String prefix = videos.size() > 1 ? "Vídeo " + (v + 1) + " · " : "";
            for (JSONObject var : videos.get(v)) {
                String url = var.getString("url");
                long kbps = var.optLong("bitrate") / 1000;
                mi.video.add(new MediaInfo.Option(prefix + resolution(url) + (kbps > 0 ? " · " + kbps + " kbps" : ""),
                        url, 0, null, 0));
            }
        }
        for (int v = 0; v < videos.size(); v++) {
            if (hasAudio.get(v)) {
                // o áudio é extraído do melhor MP4 (o X não tem trilha de áudio separada)
                mi.audio = new MediaInfo.Option("Áudio", null, 0, videos.get(v).get(0).getString("url"), 0);
                break;
            }
        }
        return mi;
    }

    private static List<JSONObject> mp4Variants(JSONArray arr) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject v = arr.getJSONObject(i);
            if ("video/mp4".equals(v.optString("content_type")) && v.has("url")) out.add(v);
        }
        Collections.sort(out, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("bitrate"), a.optLong("bitrate"));
            }
        });
        return out;
    }

    private static String resolution(String url) {
        Matcher m = Pattern.compile("/(\\d{2,4})x(\\d{2,4})/").matcher(url);
        if (!m.find()) return "MP4";
        int w = Integer.parseInt(m.group(1)), h = Integer.parseInt(m.group(2));
        return Math.min(w, h) + "p";
    }

    /** Mesmo cálculo do widget oficial: ((id / 1e15) * PI) em base 36, sem zeros e ponto. */
    static String tweetToken(String id) {
        double v = (Double.parseDouble(id) / 1e15) * Math.PI;
        long ip = (long) Math.floor(v);
        double f = v - ip;
        StringBuilder sb = new StringBuilder(Long.toString(ip, 36));
        for (int i = 0; i < 11 && f > 0; i++) {
            f *= 36;
            int d = (int) Math.floor(f);
            f -= d;
            sb.append(Character.forDigit(d, 36));
        }
        return sb.toString().replace("0", "");
    }

    // ------------------------------------------------------------------ YouTube

    /** Clientes do InnerTube que costumam entregar URLs diretas. Se o YouTube mudar, atualize aqui. */
    private static final String[][] YT_CLIENTS = {
            {"ANDROID_VR", "1.65.10", "28",
                    "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                    "deviceMake=Oculus;deviceModel=Quest 3;androidSdkVersion=32;osName=Android;osVersion=12L"},
            {"IOS", "20.10.4", "5",
                    "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
                    "deviceMake=Apple;deviceModel=iPhone16,2;osName=iPhone;osVersion=18.3.2.22D82"},
    };

    private static MediaInfo youtube(String videoId) throws Exception {
        List<String> errors = new ArrayList<>();
        for (String[] c : YT_CLIENTS) {
            try {
                MediaInfo mi = youtubeWith(videoId, c);
                if (mi != null) return mi;
                errors.add(c[0] + ": sem formatos diretos");
            } catch (Exception e) {
                errors.add(c[0] + ": " + e.getMessage());
            }
        }
        throw new Exception("O YouTube recusou (" + join(errors) + "). Vídeos privados, com restrição de idade "
                + "ou de membros não funcionam; se falhar em todos, o YouTube mudou algo e as versões de cliente "
                + "em Extractors.java precisam ser atualizadas.");
    }

    private static MediaInfo youtubeWith(String videoId, String[] c) throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", c[0]);
        client.put("clientVersion", c[1]);
        client.put("hl", "pt");
        client.put("gl", "BR");
        for (String kv : c[4].split(";")) {
            String[] p = kv.split("=", 2);
            if (p[0].equals("androidSdkVersion")) client.put(p[0], Integer.parseInt(p[1]));
            else client.put(p[0], p[1]);
        }
        JSONObject body = new JSONObject();
        body.put("context", new JSONObject().put("client", client));
        body.put("videoId", videoId);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", c[3]);
        h.put("X-YouTube-Client-Name", c[2]);
        h.put("X-YouTube-Client-Version", c[1]);
        h.put("Origin", "https://www.youtube.com");
        JSONObject r = new JSONObject(Net.postJson(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body.toString(), h));

        JSONObject ps = r.optJSONObject("playabilityStatus");
        String status = ps != null ? ps.optString("status") : "?";
        if (!"OK".equals(status)) {
            String reason = ps != null ? ps.optString("reason", status) : status;
            throw new Exception(reason.isEmpty() ? status : reason);
        }
        JSONObject sd = r.optJSONObject("streamingData");
        if (sd == null) return null;

        List<JSONObject> prog = withUrl(sd.optJSONArray("formats"), "video/mp4", null);
        List<JSONObject> vids = withUrl(sd.optJSONArray("adaptiveFormats"), "video/mp4", "avc1");
        List<JSONObject> auds = withUrl(sd.optJSONArray("adaptiveFormats"), "audio/mp4", null);

        JSONObject bestAudio = null;
        for (JSONObject a : auds) if (bestAudio == null || a.optLong("bitrate") > bestAudio.optLong("bitrate")) bestAudio = a;

        MediaInfo mi = new MediaInfo();
        mi.source = "YouTube";
        JSONObject vd = r.optJSONObject("videoDetails");
        mi.title = vd != null ? vd.optString("title", videoId) : videoId;
        mi.headers.put("User-Agent", c[3]);

        if (bestAudio != null) {
            // melhor fluxo H.264 por altura (MediaMuxer grava H.264 em MP4 em qualquer Android)
            TreeMap<Integer, JSONObject> byHeight = new TreeMap<>(Collections.<Integer>reverseOrder());
            for (JSONObject v : vids) {
                int hgt = v.optInt("height");
                JSONObject cur = byHeight.get(hgt);
                if (cur == null || v.optLong("bitrate") > cur.optLong("bitrate")) byHeight.put(hgt, v);
            }
            long aLen = bestAudio.optLong("contentLength");
            for (JSONObject v : byHeight.values()) {
                long vLen = v.optLong("contentLength");
                int fps = v.optInt("fps");
                String label = v.optInt("height") + "p" + (fps > 30 ? fps : "") + MediaInfo.mb(vLen + aLen);
                mi.video.add(new MediaInfo.Option(label, v.getString("url"), vLen, bestAudio.getString("url"), aLen));
            }
            mi.audio = new MediaInfo.Option("Áudio", null, 0, bestAudio.getString("url"), aLen);
        }
        for (JSONObject p : prog) {
            long len = p.optLong("contentLength");
            mi.video.add(new MediaInfo.Option(p.optString("qualityLabel", "MP4") + " (arquivo único)" + MediaInfo.mb(len),
                    p.getString("url"), len, null, 0));
        }
        if (mi.audio == null && !prog.isEmpty()) {
            mi.audio = new MediaInfo.Option("Áudio", null, 0, prog.get(0).getString("url"), prog.get(0).optLong("contentLength"));
        }
        if (mi.video.isEmpty() && mi.audio == null) return null;
        return mi;
    }

    private static List<JSONObject> withUrl(JSONArray arr, String mimePrefix, String codec) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.getJSONObject(i);
            String mt = f.optString("mimeType");
            if (!f.has("url") || !mt.startsWith(mimePrefix)) continue;
            if (codec != null && !mt.contains(codec)) continue;
            out.add(f);
        }
        return out;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(s);
        }
        return sb.toString();
    }
}
