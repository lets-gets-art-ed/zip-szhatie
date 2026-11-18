package fi.ilmavirta.zip;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Простое приложение: выбрать файлы -> сжать -> сохранить .zip -> поделиться.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_SAVE = 2;

    private final ArrayList<Uri> files = new ArrayList<>();

    private LinearLayout fileList;
    private TextView summary;
    private TextView status;
    private EditText nameInput;
    private CheckBox maxCompression;
    private Button zipButton;
    private Button shareButton;
    private ProgressBar progress;

    private Uri lastZip;
    private boolean working = false;

    // ---------- Жизненный цикл ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        handleIncoming(getIntent());
        refreshList();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
        refreshList();
    }

    /** Файлы, присланные через «Поделиться» из галереи, проводника и т.п. */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) addFile(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) addFile(u);
        }
    }

    // ---------- Интерфейс ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Button bigButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Сжатие файлов в ZIP");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("1. Выберите файлы  2. Нажмите «Сжать в ZIP»  3. Выберите, куда сохранить.\n"
                + "Файлы можно также отправить сюда через «Поделиться».");
        hint.setTextSize(14);
        hint.setTextColor(Color.parseColor("#555555"));
        hint.setPadding(0, dp(6), 0, dp(6));
        root.addView(hint);

        Button pick = bigButton("➕  Выбрать файлы", Color.parseColor("#1E6FD9"));
        pick.setOnClickListener(v -> pickFiles());
        root.addView(pick);

        summary = new TextView(this);
        summary.setTextSize(15);
        summary.setTypeface(Typeface.DEFAULT_BOLD);
        summary.setPadding(0, dp(14), 0, dp(4));
        root.addView(summary);

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        root.addView(fileList);

        TextView nameLabel = new TextView(this);
        nameLabel.setText("Имя архива:");
        nameLabel.setTextSize(14);
        nameLabel.setPadding(0, dp(14), 0, 0);
        root.addView(nameLabel);

        nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setText(defaultName());
        root.addView(nameInput);

        maxCompression = new CheckBox(this);
        maxCompression.setText("Максимальное сжатие (медленнее)");
        maxCompression.setChecked(true);
        root.addView(maxCompression);

        zipButton = bigButton("🗜  Сжать в ZIP", Color.parseColor("#2E9E4F"));
        zipButton.setOnClickListener(v -> askWhereToSave());
        root.addView(zipButton);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(12);
        progress.setLayoutParams(plp);
        root.addView(progress);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(10), 0, 0);
        root.addView(status);

        shareButton = bigButton("📤  Поделиться архивом", Color.parseColor("#7A4FD1"));
        shareButton.setVisibility(View.GONE);
        shareButton.setOnClickListener(v -> shareZip());
        root.addView(shareButton);

        Button clear = bigButton("Очистить список", Color.parseColor("#9AA0A6"));
        clear.setOnClickListener(v -> {
            if (working) return;
            files.clear();
            lastZip = null;
            shareButton.setVisibility(View.GONE);
            status.setText("");
            nameInput.setText(defaultName());
            refreshList();
        });
        root.addView(clear);

        return scroll;
    }

    private String defaultName() {
        return "arkisto_" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".zip";
    }

    private void refreshList() {
        fileList.removeAllViews();
        long total = 0;
        for (int i = 0; i < files.size(); i++) {
            final Uri u = files.get(i);
            long size = querySize(u);
            if (size > 0) total += size;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));

            TextView t = new TextView(this);
            t.setText("• " + queryName(u) + "  (" + human(size) + ")");
            t.setTextSize(15);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText("✕");
            del.setTextSize(20);
            del.setTextColor(Color.parseColor("#C62828"));
            del.setPadding(dp(16), dp(4), dp(8), dp(4));
            del.setOnClickListener(v -> {
                if (working) return;
                files.remove(u);
                refreshList();
            });
            row.addView(del);

            fileList.addView(row);
        }
        if (files.isEmpty()) {
            summary.setText("Файлы не выбраны");
        } else {
            summary.setText("Выбрано файлов: " + files.size() + ", общий размер: " + human(total));
        }
        zipButton.setEnabled(!files.isEmpty() && !working);
        zipButton.setAlpha(zipButton.isEnabled() ? 1f : 0.5f);
    }

    // ---------- Выбор и сохранение ----------

    private void pickFiles() {
        if (working) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    private void askWhereToSave() {
        if (files.isEmpty() || working) return;
        String name = nameInput.getText().toString().trim();
        if (name.isEmpty()) name = defaultName();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name + ".zip";
        nameInput.setText(name);

        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_PICK) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) addFile(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                addFile(data.getData());
            }
            refreshList();
        } else if (requestCode == REQ_SAVE && data.getData() != null) {
            startZipping(data.getData());
        }
    }

    private void addFile(Uri u) {
        if (u != null && !files.contains(u)) files.add(u);
    }

    // ---------- Сжатие ----------

    private void startZipping(final Uri target) {
        working = true;
        shareButton.setVisibility(View.GONE);
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        status.setText("Сжимаю…");
        refreshList();

        final ArrayList<Uri> todo = new ArrayList<>(files);
        final int level = maxCompression.isChecked() ? Deflater.BEST_COMPRESSION : Deflater.DEFAULT_COMPRESSION;

        new Thread(() -> {
            long totalIn = 0;
            for (Uri u : todo) {
                long s = querySize(u);
                if (s > 0) totalIn += s;
            }
            final long grandTotal = totalIn;
            long done = 0;
            long written;
            String error = null;
            Set<String> usedNames = new HashSet<>();
            CountingStream counter = null;

            try (OutputStream raw = getContentResolver().openOutputStream(target, "wt")) {
                if (raw == null) throw new IOException("не удалось открыть файл для записи");
                counter = new CountingStream(raw);
                ZipOutputStream zip = new ZipOutputStream(counter);
                zip.setLevel(level);
                byte[] buf = new byte[64 * 1024];

                for (int idx = 0; idx < todo.size(); idx++) {
                    Uri u = todo.get(idx);
                    String name = uniqueName(queryName(u), usedNames);
                    final int fileNo = idx + 1;
                    final String shown = name;
                    runOnUiThread(() -> status.setText("Сжимаю " + fileNo + " из " + todo.size() + ": " + shown));

                    try (InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null) throw new IOException("не удалось прочитать " + name);
                        zip.putNextEntry(new ZipEntry(name));
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            zip.write(buf, 0, n);
                            done += n;
                            if (grandTotal > 0) {
                                final int p = (int) Math.min(1000, done * 1000 / grandTotal);
                                runOnUiThread(() -> progress.setProgress(p));
                            }
                        }
                        zip.closeEntry();
                    }
                }
                zip.finish();
                zip.flush();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }

            written = counter == null ? 0 : counter.count;
            final String err = error;
            final long inBytes = done;
            final long outBytes = written;
            runOnUiThread(() -> finishZipping(target, err, inBytes, outBytes, todo.size()));
        }).start();
    }

    private void finishZipping(Uri target, String error, long in, long out, int count) {
        working = false;
        progress.setVisibility(View.GONE);
        if (error != null) {
            status.setTextColor(Color.parseColor("#C62828"));
            status.setText("Ошибка: " + error);
        } else {
            lastZip = target;
            status.setTextColor(Color.parseColor("#2E7D32"));
            String saved = in > 0 ? Math.max(0, Math.round(100.0 - out * 100.0 / in)) + "%" : "—";
            status.setText("✔ Готово! Файлов в архиве: " + count
                    + "\nБыло: " + human(in) + "  →  стало: " + human(out)
                    + "\nЭкономия: " + saved
                    + "\n(Фото, видео и PDF почти не сжимаются — они уже сжаты.)");
            shareButton.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Архив сохранён", Toast.LENGTH_SHORT).show();
        }
        refreshList();
    }

    private void shareZip() {
        if (lastZip == null) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("application/zip");
        send.putExtra(Intent.EXTRA_STREAM, lastZip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Отправить архив"));
    }

    // ---------- Вспомогательное ----------

    private String queryName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = u.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "file";
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
        }
        return name.replace('\\', '_').replace('/', '_');
    }

    private long querySize(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** Если два файла называются одинаково — второй станет «имя (2).ext». */
    private static String uniqueName(String name, Set<String> used) {
        if (used.add(name.toLowerCase(Locale.ROOT))) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (used.add(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }

    private static String human(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " Б";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb);
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0);
    }

    /** Считает, сколько байт записано в архив. */
    private static class CountingStream extends FilterOutputStream {
        long count = 0;

        CountingStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }
    }
}
