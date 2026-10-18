package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.mobilehorizon.compat.BackendCapability;
import dev.mobilehorizon.compat.BackendDescriptor;
import dev.mobilehorizon.compat.CompatibilityChecker;
import dev.mobilehorizon.compat.CompatibilityReport;
import dev.mobilehorizon.compat.DeviceCapabilities;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int REQUEST_IMPORT_FOLDER = 101;
    private static final int REQUEST_IMPORT_ZIP = 102;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private TextView statusTitle;
    private TextView statusDetails;
    private TextView compatibilityText;
    private TextView progressDetails;
    private ProgressBar progressBar;
    private Button importFolderButton;
    private Button importZipButton;
    private Button cancelButton;
    private boolean busy;
    private Uri pendingUri;
    private boolean pendingZip;
    private Future<?> currentJob;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(244, 246, 251));
        getWindow().setNavigationBarColor(Color.rgb(244, 246, 251));
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        buildUi();
        updateCompatibility();
        setStatus("Pronto para importar",
                "Selecione uma pasta preparada ou um ZIP. Os arquivos serão copiados para o armazenamento privado do app.");
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(244, 246, 251));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        TextView eyebrow = text("MOBILEHORIZON  /  NATIVO", 12, 0xff5363bd, true);
        page.addView(eyebrow, params(-1, -2));

        TextView title = text("Preparação no Android", 28, 0xff17213b, true);
        LinearLayout.LayoutParams titleParams = params(-1, -2);
        titleParams.topMargin = dp(8);
        page.addView(title, titleParams);

        TextView intro = text("Importação local, validação inicial e compatibilidade explícita — sem exigir Termux para importar arquivos.", 15, 0xff59647b, false);
        LinearLayout.LayoutParams introParams = params(-1, -2);
        introParams.topMargin = dp(8);
        page.addView(intro, introParams);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams cardParams = params(-1, -2);
        cardParams.topMargin = dp(22);
        page.addView(statusCard, cardParams);
        statusTitle = text("Pronto para importar", 17, 0xff18213a, true);
        statusCard.addView(statusTitle, params(-1, -2));
        statusDetails = text("", 14, 0xff59647b, false);
        LinearLayout.LayoutParams detailsParams = params(-1, -2);
        detailsParams.topMargin = dp(8);
        statusCard.addView(statusDetails, detailsParams);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(1000);
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = params(-1, dp(8));
        progressParams.topMargin = dp(18);
        statusCard.addView(progressBar, progressParams);

        progressDetails = text("", 13, 0xff59647b, false);
        LinearLayout.LayoutParams progressTextParams = params(-1, -2);
        progressTextParams.topMargin = dp(8);
        statusCard.addView(progressDetails, progressTextParams);

        TextView actionsHeading = text("Importar arquivos", 18, 0xff18213a, true);
        LinearLayout.LayoutParams actionsParams = params(-1, -2);
        actionsParams.topMargin = dp(24);
        page.addView(actionsHeading, actionsParams);

        TextView actionsNote = text("A importação usa o seletor de arquivos do Android e não solicita acesso amplo ao armazenamento.", 14, 0xff59647b, false);
        LinearLayout.LayoutParams noteParams = params(-1, -2);
        noteParams.topMargin = dp(6);
        page.addView(actionsNote, noteParams);

        importFolderButton = button("Selecionar pasta preparada", true, v -> chooseFolder());
        LinearLayout.LayoutParams folderParams = params(-1, dp(52));
        folderParams.topMargin = dp(16);
        page.addView(importFolderButton, folderParams);

        importZipButton = button("Selecionar arquivo ZIP", false, v -> chooseZip());
        LinearLayout.LayoutParams zipParams = params(-1, dp(52));
        zipParams.topMargin = dp(10);
        page.addView(importZipButton, zipParams);

        cancelButton = button("Cancelar importação", false, v -> cancelImport());
        LinearLayout.LayoutParams cancelParams = params(-1, dp(48));
        cancelParams.topMargin = dp(10);
        cancelButton.setVisibility(View.GONE);
        page.addView(cancelButton, cancelParams);

        LinearLayout compatibilityCard = card();
        LinearLayout.LayoutParams compatParams = params(-1, -2);
        compatParams.topMargin = dp(22);
        page.addView(compatibilityCard, compatParams);
        compatibilityCard.addView(text("Camada de compatibilidade", 16, 0xff18213a, true), params(-1, -2));
        compatibilityText = text("", 14, 0xff59647b, false);
        LinearLayout.LayoutParams compatTextParams = params(-1, -2);
        compatTextParams.topMargin = dp(8);
        compatibilityCard.addView(compatibilityText, compatTextParams);

        TextView footer = text("Este marco importa e verifica a estrutura básica dos arquivos. O backend de preparação de recursos e o motor Horizon ainda são componentes separados; importar não significa que o jogo já esteja pronto para iniciar.", 12, 0xff68728a, false);
        LinearLayout.LayoutParams footerParams = params(-1, -2);
        footerParams.topMargin = dp(20);
        page.addView(footer, footerParams);
    }

    private void updateCompatibility() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            if (manager != null) manager.getMemoryInfo(memory);
            DeviceCapabilities device = new DeviceCapabilities(
                    Build.VERSION.SDK_INT,
                    Arrays.asList(Build.SUPPORTED_ABIS),
                    Build.VERSION.SDK_INT >= 19,
                    manager == null ? null : memory.availMem);
            BackendDescriptor importer = new BackendDescriptor(
                    "android-saf-importer",
                    BuildConfig.VERSION_NAME,
                    Collections.emptySet(),
                    26,
                    EnumSet.of(BackendCapability.IMPORT_OUTPUT, BackendCapability.REPORT_PROGRESS),
                    null);
            CompatibilityReport report = CompatibilityChecker.INSTANCE.check(device, importer);
            compatibilityText.setText("Estado: " + report.getStatus().name()
                    + "\nAndroid API: " + Build.VERSION.SDK_INT
                    + "\nABIs reportadas: " + String.join(", ", Build.SUPPORTED_ABIS)
                    + "\n\nA verificação cobre esta etapa de importação. Ela não certifica compatibilidade gráfica nem inicialização de um motor.");
        } catch (Throwable error) {
            compatibilityText.setText("A verificação detalhada da camada de compatibilidade não foi concluída: "
                    + safeMessage(error));
        }
    }

    private void chooseFolder() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        startActivityForResult(intent, REQUEST_IMPORT_FOLDER);
    }

    private void chooseZip() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_IMPORT_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        boolean zip = requestCode == REQUEST_IMPORT_ZIP;
        if (!zip && requestCode != REQUEST_IMPORT_FOLDER) return;
        pendingUri = data.getData();
        pendingZip = zip;
        try {
            getContentResolver().takePersistableUriPermission(pendingUri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        } catch (SecurityException ignored) {
            // Some document providers offer only a temporary read grant; the import runs immediately.
        }
        File active = new File(getFilesDir(), "prepared-resources");
        if (active.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("Substituir importação atual?")
                    .setMessage("A importação nova só será ativada depois de terminar e passar pela validação inicial. A cópia anterior será preservada se ocorrer uma falha.")
                    .setNegativeButton("Manter atual", (dialog, which) -> clearPendingImport())
                    .setPositiveButton("Substituir", (dialog, which) -> beginImport())
                    .show();
        } else {
            beginImport();
        }
    }

    private void beginImport() {
        if (busy || pendingUri == null) return;
        final Uri source = pendingUri;
        final boolean isZip = pendingZip;
        pendingUri = null;
        busy = true;
        cancelRequested.set(false);
        setBusyUi(true);
        progressBar.setIndeterminate(isZip);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.VISIBLE);
        progressDetails.setText(isZip ? "Preparando leitura do arquivo ZIP…" : "Analisando a pasta selecionada…");
        setStatus("Importação em andamento", "Os dados estão sendo copiados para uma área temporária. Você pode cancelar sem substituir a importação anterior.");
        currentJob = executor.submit(() -> {
            try {
                PreparedResourceImporter.ProgressListener listener = progress ->
                        runOnUiThread(() -> updateProgress(progress, isZip));
                PreparedResourceImporter.ImportSummary summary = isZip
                        ? PreparedResourceImporter.importZip(getContentResolver(), source, getFilesDir(), cancelRequested, listener)
                        : PreparedResourceImporter.importTree(getContentResolver(), source, getFilesDir(), cancelRequested, listener);
                runOnUiThread(() -> finishImport(summary, null));
            } catch (Exception error) {
                runOnUiThread(() -> finishImport(null, error));
            }
        });
    }

    private void updateProgress(PreparedResourceImporter.Progress progress, boolean isZip) {
        if (!busy || isFinishing() || isDestroyed()) return;
        if (isZip) {
            progressBar.setIndeterminate(true);
        } else if (progress.totalBytes > 0) {
            progressBar.setIndeterminate(false);
            int value = (int) Math.min(1000, (progress.bytesProcessed * 1000L) / progress.totalBytes);
            progressBar.setProgress(value);
        } else if (progress.totalFiles > 0) {
            progressBar.setIndeterminate(false);
            int value = (int) Math.min(1000, (progress.completedFiles * 1000L) / progress.totalFiles);
            progressBar.setProgress(value);
        } else {
            progressBar.setIndeterminate(true);
        }
        String files = progress.completedFiles + (progress.totalFiles > 0 ? " / " + progress.totalFiles : "");
        String bytes = formatBytes(progress.bytesProcessed);
        if (progress.totalBytes > 0) bytes += " / " + formatBytes(progress.totalBytes);
        progressDetails.setText(progress.stage + "\nArquivos: " + files + "\nDados copiados: " + bytes);
    }

    private void finishImport(PreparedResourceImporter.ImportSummary summary, Exception error) {
        if (isFinishing() || isDestroyed()) return;
        busy = false;
        currentJob = null;
        setBusyUi(false);
        progressBar.setIndeterminate(false);
        if (error == null && summary != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(1000);
            progressDetails.setText(summary.files + " arquivos · " + formatBytes(summary.bytes));
            setStatus("Importação concluída", "Destino: armazenamento privado do app\n" + summary.validationNote
                    + "\n\nOs arquivos anteriores só foram substituídos depois da conclusão da importação.");
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText("");
            if (error instanceof InterruptedException || cancelRequested.get()) {
                setStatus("Importação cancelada", "A pasta temporária foi descartada. A importação anterior, se existia, não foi substituída.");
            } else {
                setStatus("Não foi possível importar", safeMessage(error)
                        + "\n\nA importação anterior, se existia, foi mantida.");
            }
        }
    }

    private void cancelImport() {
        if (!busy) return;
        cancelRequested.set(true);
        cancelButton.setEnabled(false);
        progressDetails.setText("Cancelamento solicitado. A operação vai parar em um ponto seguro…");
    }

    private void clearPendingImport() {
        pendingUri = null;
    }

    private void setBusyUi(boolean importing) {
        importFolderButton.setEnabled(!importing);
        importZipButton.setEnabled(!importing);
        cancelButton.setVisibility(importing ? View.VISIBLE : View.GONE);
        cancelButton.setEnabled(importing);
        importFolderButton.setAlpha(importing ? 0.55f : 1f);
        importZipButton.setAlpha(importing ? 0.55f : 1f);
    }

    private void setStatus(String title, String details) {
        statusTitle.setText(title);
        statusDetails.setText(details);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(18));
        background.setStroke(dp(1), 0xffe2e6f0);
        card.setBackground(background);
        return card;
    }

    private Button button(String label, boolean primary, View.OnClickListener click) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(primary ? 0xff4259d9 : Color.WHITE);
        if (!primary) background.setStroke(dp(1), 0xffd9dfed);
        button.setBackground(background);
        button.setTextColor(primary ? Color.WHITE : 0xff24304c);
        button.setOnClickListener(click);
        return button;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(sizeSp);
        text.setTextColor(color);
        text.setGravity(Gravity.START);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "desconhecido";
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            cancelRequested.set(true);
            if (currentJob != null) currentJob.cancel(true);
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
