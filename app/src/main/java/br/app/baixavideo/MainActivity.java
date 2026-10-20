package br.app.baixavideo;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText input;
    private Button btnPaste, btnFetch, btnVideo, btnAudio, btnCancel, btnOpen;
    private TextView info, qualityLabel, status;
    private Spinner quality;
    private ProgressBar progress;

    private MediaInfo current;
    private AtomicBoolean cancel = new AtomicBoolean();
    private Downloader.Result lastSaved;
    private boolean busy;
    private int lastPermille = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String url = Extractors.findUrl(i.getStringExtra(Intent.EXTRA_TEXT));
        if (url != null && !busy) {
            input.setText(url);
            fetch();
        }
    }

    // ------------------------------------------------------------------ UI

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        sv.addView(root);

        TextView title = text("BaixaVídeo", 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Cole um link do X (Twitter) ou do YouTube — ou use \"Compartilhar\" no app e escolha o BaixaVídeo.", 14), lp(4));

        input = new EditText(this);
        input.setHint("https://x.com/…  ou  https://youtu.be/…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                fetch();
                return true;
            }
        });
        root.addView(input, lp(16));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        btnPaste = button("Colar");
        btnFetch = button("Buscar");
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        row.addView(btnPaste, half);
        row.addView(btnFetch, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row, lp(8));

        info = text("", 16);
        info.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(info, lp(20));

        qualityLabel = text("Qualidade do vídeo", 13);
        root.addView(qualityLabel, lp(12));
        quality = new Spinner(this);
        root.addView(quality, lp(4));

        btnVideo = button("Baixar vídeo (.mp4)");
        root.addView(btnVideo, lp(12));
        btnAudio = button("Baixar só o áudio (.mp3)");
        root.addView(btnAudio, lp(4));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        root.addView(progress, lp(20));
        status = text("", 14);
        root.addView(status, lp(6));

        btnCancel = button("Cancelar");
        root.addView(btnCancel, lp(8));
        btnOpen = button("Abrir arquivo");
        root.addView(btnOpen, lp(8));

        btnPaste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData clip = cm != null ? cm.getPrimaryClip() : null;
                if (clip != null && clip.getItemCount() > 0) {
                    CharSequence cs = clip.getItemAt(0).coerceToText(MainActivity.this);
                    String url = Extractors.findUrl(cs == null ? null : cs.toString());
                    input.setText(url != null ? url : (cs == null ? "" : cs.toString()));
                    if (url != null) fetch();
                } else {
                    setStatus("A área de transferência está vazia.");
                }
            }
        });
        btnFetch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fetch();
            }
        });
        btnVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload(false);
            }
        });
        btnAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDownload(true);
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancel.set(true);
                setStatus("Cancelando…");
            }
        });
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLast();
            }
        });

        showOptions(false);
        progress.setVisibility(View.GONE);
        btnCancel.setVisibility(View.GONE);
        btnOpen.setVisibility(View.GONE);
        setContentView(sv);
    }

    private void showOptions(boolean show) {
        int vis = show ? View.VISIBLE : View.GONE;
        boolean hasVideo = show && current != null && !current.video.isEmpty();
        qualityLabel.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        quality.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        btnVideo.setVisibility(hasVideo ? View.VISIBLE : View.GONE);
        btnAudio.setVisibility(show && current != null && current.audio != null ? View.VISIBLE : View.GONE);
        info.setVisibility(vis);
    }

    private void setBusy(boolean b) {
        busy = b;
        btnPaste.setEnabled(!b);
        btnFetch.setEnabled(!b);
        btnVideo.setEnabled(!b);
        btnAudio.setEnabled(!b);
        quality.setEnabled(!b);
        input.setEnabled(!b);
        if (b) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ ações

    private void fetch() {
        if (busy) return;
        final String text = input.getText().toString().trim();
        final String url = Extractors.findUrl(text) != null ? Extractors.findUrl(text) : text;
        if (url.isEmpty()) {
            setStatus("Cole um link primeiro.");
            return;
        }
        hideKeyboard();
        current = null;
        showOptions(false);
        btnOpen.setVisibility(View.GONE);
        setBusy(true);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        setStatus("Buscando informações…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final MediaInfo mi = Extractors.extract(url);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            current = mi;
                            info.setText(mi.title + "\n" + mi.source);
                            List<String> labels = new ArrayList<>();
                            for (MediaInfo.Option o : mi.video) labels.add(o.label);
                            ArrayAdapter<String> ad = new ArrayAdapter<>(MainActivity.this,
                                    android.R.layout.simple_spinner_dropdown_item, labels);
                            quality.setAdapter(ad);
                            setBusy(false);
                            progress.setVisibility(View.GONE);
                            showOptions(true);
                            setStatus(mi.video.isEmpty() ? "Só o áudio está disponível." : "Escolha a qualidade e baixe.");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            setBusy(false);
                            progress.setVisibility(View.GONE);
                            setStatus("Erro: " + message(e));
                        }
                    });
                }
            }
        });
    }

    private void startDownload(final boolean audioOnly) {
        final MediaInfo mi = current;
        if (mi == null || busy) return;
        final MediaInfo.Option opt = audioOnly ? mi.audio : mi.video.get(Math.max(0, quality.getSelectedItemPosition()));
        if (opt == null) return;
        cancel = new AtomicBoolean(false);
        final AtomicBoolean myCancel = cancel;
        lastPermille = -1;
        setBusy(true);
        btnOpen.setVisibility(View.GONE);
        btnCancel.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        setStatus("Começando…");

        final Downloader.Listener listener = new Downloader.Listener() {
            private volatile String stage = "";

            @Override
            public void onStage(final String text) {
                stage = text;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        setStatus(text);
                        lastPermille = -1;
                        progress.setIndeterminate(true);
                    }
                });
            }

            @Override
            public void onProgress(final long done, final long total) {
                final int permille = total > 0 ? (int) Math.min(1000, done * 1000 / total) : -1;
                if (permille == lastPermille && permille >= 0) return;
                if (permille < 0 && (done % (1024 * 1024)) > 65536) return; // atualiza ~a cada MB
                lastPermille = permille;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (permille >= 0) {
                            progress.setIndeterminate(false);
                            progress.setProgress(permille);
                            if (stage.startsWith("Baixando")) {
                                setStatus(stage + "  " + (permille / 10) + "%  (" + (done / 1048576) + " / " + (total / 1048576) + " MB)");
                            } else {
                                setStatus(stage + "  " + (permille / 10) + "%");
                            }
                        } else {
                            setStatus(stage + "  " + (done / 1048576) + " MB");
                        }
                    }
                });
            }
        };

        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Downloader.Result r = Downloader.run(getApplicationContext(), mi, opt, audioOnly, myCancel, listener);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            lastSaved = r;
                            finishDownload("Pronto! Salvo em Download/" + r.name);
                            btnOpen.setVisibility(View.VISIBLE);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            finishDownload(e instanceof Net.CancelledException ? "Cancelado." : "Erro: " + message(e));
                        }
                    });
                }
            }
        });
    }

    private void finishDownload(String msg) {
        setBusy(false);
        btnCancel.setVisibility(View.GONE);
        progress.setIndeterminate(false);
        progress.setVisibility(View.GONE);
        setStatus(msg);
    }

    private void openLast() {
        if (lastSaved == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(lastSaved.uri, lastSaved.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            setStatus("Nenhum app para abrir esse arquivo. Ele está na pasta Download.");
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        if (e instanceof java.net.UnknownHostException) return "sem internet.";
        if (e instanceof java.net.SocketTimeoutException) return "a conexão demorou demais. Tente de novo.";
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    @Override
    protected void onDestroy() {
        cancel.set(true);
        worker.shutdownNow();
        super.onDestroy();
    }
}
