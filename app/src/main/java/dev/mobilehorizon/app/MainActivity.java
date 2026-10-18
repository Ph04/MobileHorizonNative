package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.ApplicationInfo;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.List;
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
    private Button downloadBaseButton;
    private Button downloadAssetsButton;
    private Button cancelDownloadButton;
    private Button prepareInputsButton;
    private Button openEngineButton;
    private Button smokeTestButton;
    private boolean busy;
    private boolean conversionBusy;
    private boolean downloadBusy;
    private boolean receiverRegistered;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (OfficialDownloadService.ACTION_PROGRESS.equals(intent.getAction())) {
                handleDownloadProgress(intent);
            } else if (OfficialDownloadService.ACTION_FINISHED.equals(intent.getAction())) {
                handleDownloadFinished(intent);
            }
        }
    };
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
        setStatus("Pronto",
                "Baixe os arquivos oficiais do Minecraft 26.3, prepare as entradas para conversão ou importe uma saída Horizon já preparada.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(OfficialDownloadService.ACTION_PROGRESS);
        filter.addAction(OfficialDownloadService.ACTION_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
        try {
            startService(new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_QUERY));
        } catch (Exception ignored) {
            // The query is only for restoring a progress display; it must not interrupt startup.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showNativeSmokeResultIfAvailable();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
        super.onStop();
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

        TextView downloadHeading = text("Minecraft Java 26.3", 18, 0xff18213a, true);
        LinearLayout.LayoutParams downloadHeadingParams = params(-1, -2);
        downloadHeadingParams.topMargin = dp(24);
        page.addView(downloadHeading, downloadHeadingParams);

        TextView downloadNote = text("Baixe os arquivos diretamente dos servidores oficiais. O app verifica os tamanhos e SHA-1 antes de ativar cada arquivo. Os assets completos são opcionais e podem ocupar centenas de MB.", 14, 0xff59647b, false);
        LinearLayout.LayoutParams downloadNoteParams = params(-1, -2);
        downloadNoteParams.topMargin = dp(6);
        page.addView(downloadNote, downloadNoteParams);

        downloadBaseButton = button("Baixar arquivos base oficiais", true, v -> startOfficialDownload(false));
        LinearLayout.LayoutParams downloadBaseParams = params(-1, dp(52));
        downloadBaseParams.topMargin = dp(14);
        page.addView(downloadBaseButton, downloadBaseParams);

        downloadAssetsButton = button("Baixar base + todos os assets", false, v -> confirmFullAssetDownload());
        LinearLayout.LayoutParams downloadAssetsParams = params(-1, dp(52));
        downloadAssetsParams.topMargin = dp(8);
        page.addView(downloadAssetsButton, downloadAssetsParams);

        cancelDownloadButton = button("Cancelar download", false, v -> cancelOfficialDownload());
        LinearLayout.LayoutParams cancelDownloadParams = params(-1, dp(48));
        cancelDownloadParams.topMargin = dp(8);
        cancelDownloadButton.setVisibility(View.GONE);
        page.addView(cancelDownloadButton, cancelDownloadParams);

        prepareInputsButton = button("Preparar recursos vanilla para conversão", false, v -> prepareConversionInputs());
        LinearLayout.LayoutParams prepareInputsParams = params(-1, dp(50));
        prepareInputsParams.topMargin = dp(8);
        page.addView(prepareInputsButton, prepareInputsParams);

        openEngineButton = button("Verificar requisitos do cliente Horizon", false, v -> openNativeClient());
        LinearLayout.LayoutParams openEngineParams = params(-1, dp(50));
        openEngineParams.topMargin = dp(8);
        page.addView(openEngineButton, openEngineParams);

        smokeTestButton = button("Testar renderer Vulkan", false, v -> openNativeSmokeTest());
        LinearLayout.LayoutParams smokeParams = params(-1, dp(50));
        smokeParams.topMargin = dp(8);
        page.addView(smokeTestButton, smokeParams);

        TextView actionsHeading = text("Importar saída já preparada", 18, 0xff18213a, true);
        LinearLayout.LayoutParams actionsParams = params(-1, -2);
        actionsParams.topMargin = dp(24);
        page.addView(actionsHeading, actionsParams);

        TextView actionsNote = text("Selecione a pasta final 26.3 que contém horizon-resources.json na raiz, ou um ZIP dessa mesma pasta. A importação verifica os arquivos exigidos pelo motor e instala a saída em .local/horizon-client/26.3.", 14, 0xff59647b, false);
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

        TextView footer = text("Estado honesto do motor: os JARs oficiais e as bibliotecas podem ser baixados e verificados aqui. A conversão completa dos JARs para os recursos Horizon ainda precisa do exportador nativo; para abrir o jogo, é necessária uma saída Horizon completa. O teste Vulkan verifica apenas a inicialização gráfica, não o gameplay.", 12, 0xff68728a, false);
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
        File active = new File(getFilesDir(), ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
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
            setStatus("Recursos Horizon importados", "Destino: .local/horizon-client/" + OfficialMinecraftInstaller.VERSION + "\n" + summary.validationNote
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
        if (!busy && !conversionBusy) return;
        cancelRequested.set(true);
        cancelButton.setEnabled(false);
        progressDetails.setText("Cancelamento solicitado. A operação vai parar em um ponto seguro…");
    }

    private void confirmFullAssetDownload() {
        startOfficialDownload(true);
    }

    private void startOfficialDownload(boolean allAssets) {
        if (downloadBusy || busy || conversionBusy) return;
        String extra = allAssets
                ? "\\n\\nEsta opção baixa milhares de objetos e pode usar centenas de MB."
                : "";
        new AlertDialog.Builder(this)
                .setTitle("Baixar arquivos oficiais do Minecraft?")
                .setMessage("Este fluxo baixa arquivos oficiais da Mojang, verifica tamanho e SHA-1, mas não autentica uma conta nem concede uma licença. Prossiga apenas se você tiver uma licença válida de Minecraft Java Edition." + extra)
                .setNegativeButton("Voltar", null)
                .setPositiveButton("Continuar", (dialog, which) -> startOfficialDownloadNow(allAssets))
                .show();
    }

    private void startOfficialDownloadNow(boolean allAssets) {
        if (downloadBusy || busy || conversionBusy) return;
        downloadBusy = true;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Iniciando serviço de download em segundo plano…");
        setStatus(allAssets ? "Baixando Minecraft e assets completos" : "Baixando arquivos base oficiais",
                "Destino privado: files/minecraft. Arquivos só são ativados depois da verificação SHA-1 e tamanho.");
        try {
            Intent intent = new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_START)
                    .putExtra(OfficialDownloadService.EXTRA_ALL_ASSETS, allAssets);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (Exception error) {
            downloadBusy = false;
            updateActionEnablement();
            progressBar.setVisibility(View.GONE);
            setStatus("Não foi possível iniciar o download", safeMessage(error));
        }
    }

    private void cancelOfficialDownload() {
        if (!downloadBusy) return;
        cancelDownloadButton.setEnabled(false);
        progressDetails.setText("Cancelamento solicitado. Arquivos parciais não verificados serão descartados.");
        try {
            startService(new Intent(this, OfficialDownloadService.class)
                    .setAction(OfficialDownloadService.ACTION_CANCEL));
        } catch (Exception error) {
            setStatus("Falha ao solicitar cancelamento", safeMessage(error));
        }
    }

    private void handleDownloadProgress(Intent intent) {
        downloadBusy = true;
        updateActionEnablement();
        String stage = intent.getStringExtra("stage");
        String item = intent.getStringExtra("item");
        int completed = intent.getIntExtra("completed", 0);
        int total = intent.getIntExtra("total", -1);
        long itemBytes = intent.getLongExtra("itemBytes", 0);
        long itemTotalBytes = intent.getLongExtra("itemTotalBytes", -1);
        long completedBytes = intent.getLongExtra("completedBytes", 0);
        long totalBytes = intent.getLongExtra("totalBytes", -1);
        float fraction = -1f;
        if (total > 0) {
            fraction = Math.max(0f, Math.min(1f, (float) completed / total));
            if (itemTotalBytes > 0) {
                fraction = Math.max(fraction, Math.min(1f,
                        ((float) completed + Math.min(1f, (float) itemBytes / itemTotalBytes)) / total));
            }
        } else if (itemTotalBytes > 0) {
            fraction = Math.max(0f, Math.min(1f, (float) itemBytes / itemTotalBytes));
        }
        progressBar.setIndeterminate(fraction < 0f);
        if (fraction >= 0f) progressBar.setProgress(Math.round(fraction * 1000f));
        String fileCount = total > 0 ? completed + " / " + total : "não definido";
        String bytes = formatBytes(itemBytes);
        if (itemTotalBytes > 0) bytes += " / " + formatBytes(itemTotalBytes);
        String aggregate = formatBytes(completedBytes);
        if (totalBytes > 0) aggregate += " / " + formatBytes(totalBytes);
        progressDetails.setText((stage == null ? "Download" : stage)
                + (item == null || item.isEmpty() ? "" : "\nItem: " + item)
                + "\nContagem: " + fileCount
                + "\nArquivo atual: " + bytes
                + "\nTotal conhecido: " + aggregate);
        setStatus("Download oficial em andamento", "O serviço pode continuar mesmo se você sair desta tela. O sistema também mostra uma notificação de progresso.");
    }

    private void handleDownloadFinished(Intent intent) {
        boolean success = intent.getBooleanExtra("success", false);
        String message = intent.getStringExtra("message");
        int libraries = intent.getIntExtra("libraries", 0);
        int assets = intent.getIntExtra("assets", 0);
        String manifestSha1 = intent.getStringExtra("versionSha1");
        downloadBusy = false;
        cancelDownloadButton.setEnabled(true);
        updateActionEnablement();
        progressBar.setIndeterminate(false);
        if (success) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(1000);
            progressDetails.setText("Destino: files/minecraft\nBibliotecas: " + libraries
                    + (assets > 0 ? "\nAssets baixados: " + assets : "\nAssets completos: ainda não baixados")
                    + (manifestSha1 == null || manifestSha1.isEmpty() ? "" : "\nSHA-1 do JSON: " + manifestSha1));
            setStatus("Download oficial concluído", message == null ? "Arquivos verificados." : message);
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText("");
            setStatus("Download não concluído", message == null ? "Verifique a conexão e tente novamente." : message);
        }
    }

    private void prepareConversionInputs() {
        if (busy || conversionBusy || downloadBusy) return;
        conversionBusy = true;
        cancelRequested.set(false);
        currentJob = null;
        updateActionEnablement();
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressDetails.setText("Verificando manifesto, JARs, bibliotecas e índice de assets…");
        setStatus("Preparando entradas para conversão", "Esta etapa organiza e valida as entradas oficiais. Ela ainda não gera os arquivos derivados do Horizon.");
        currentJob = executor.submit(() -> {
            try {
                HorizonResourceConverter.InputReport report = HorizonResourceConverter.prepareConversionInputs(
                        getFilesDir(), cancelRequested,
                        (stage, complete, total, detail) -> runOnUiThread(() -> {
                            if (!conversionBusy || isFinishing() || isDestroyed()) return;
                            progressBar.setIndeterminate(total <= 0);
                            if (total > 0) progressBar.setProgress((int) Math.min(1000, complete * 1000L / total));
                            progressDetails.setText(stage + "\n" + detail
                                    + (total > 0 ? "\nEtapa: " + complete + " / " + total : ""));
                        }));
                runOnUiThread(() -> finishConversion(report, null));
            } catch (Exception error) {
                runOnUiThread(() -> finishConversion(null, error));
            }
        });
    }

    private void finishConversion(HorizonResourceConverter.InputReport report, Exception error) {
        if (isFinishing() || isDestroyed()) return;
        conversionBusy = false;
        currentJob = null;
        updateActionEnablement();
        progressBar.setIndeterminate(false);
        if (error == null && report != null) {
            progressBar.setProgress(1000);
            progressDetails.setText("Plano criado: .local/horizon-input/26.3/conversion-plan.json"
                    + "\nBibliotecas verificadas: " + report.verifiedLibraries
                    + "\nAssets encontrados: " + report.presentAssetObjects + " / " + report.uniqueAssets);
            setStatus("Entradas validadas — exportador pendente", report.note
                    + "\n\nEste passo não produz .local/horizon-client/26.3 nem torna o jogo jogável. O próximo marco é portar o exportador Horizon para código executável no Android.");
        } else {
            progressBar.setVisibility(View.GONE);
            progressDetails.setText("");
            setStatus("Preparação para conversão falhou", safeMessage(error));
        }
    }

    private void openNativeClient() {
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca Horizon ausente",
                    "O APK precisa conter libhorizon_android.so para ARM64 antes que o cliente possa ser usado.");
            return;
        }
        File resources = new File(getFilesDir(), ".local/horizon-client/" + OfficialMinecraftInstaller.VERSION);
        try {
            String resourceSummary = HorizonResourceConverter.validatePreparedResources(resources);
            showRuntimeError("Recursos válidos; autenticação pendente",
                    resourceSummary + "\\n\\nA integração oficial de autenticação Microsoft/Minecraft e a validação da licença Java ainda não estão implementadas. O cliente não será iniciado em modo offline. O teste Vulkan continua disponível separadamente.");
        } catch (Exception error) {
            showRuntimeError("Recursos Horizon ausentes ou incompletos",
                    safeMessage(error) + "\\n\\nBaixar JARs e extrair assets vanilla ainda não cria a tabela final de gameplay/worldgen. Importe uma saída Horizon completa enquanto o exportador nativo é portado.");
        }
    }

    private void showNativeSmokeResultIfAvailable() {
        File resultFile = new File(getFilesDir(), "horizon-native-smoke-result.txt");
        if (!resultFile.isFile()) return;
        try (FileInputStream input = new FileInputStream(resultFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 32 * 1024) {
                    throw new IllegalStateException("O relatório do teste Vulkan excedeu 32 KiB.");
                }
                output.write(buffer, 0, read);
            }
            String result = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            boolean passed = result.startsWith("PASS:");
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(false);
            progressBar.setProgress(passed ? 1000 : 0);
            progressDetails.setText(result);
            setStatus(passed ? "Renderer Vulkan validado" : "Teste do renderer falhou",
                    result + "\\n\\nEsse resultado verifica o NativeActivity e a apresentação gráfica; não comprova carregamento de recursos ou gameplay.");
            resultFile.delete();
        } catch (Exception error) {
            setStatus("Não foi possível ler o relatório Vulkan", safeMessage(error));
        }
    }

    private void openNativeSmokeTest() {
        if (!isHorizonNativeLibraryAvailable()) {
            showRuntimeError("Biblioteca nativa ausente",
                    "O teste Vulkan exige libhorizon_android.so empacotada para arm64-v8a.");
            return;
        }
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            showRuntimeError("Arquitetura não suportada", "O teste de renderer requer ARM64.");
            return;
        }
        File marker = new File(getFilesDir(), "horizon-native-smoke.flag");
        try (FileOutputStream output = new FileOutputStream(marker, false)) {
            output.write("vulkan-smoke-v1".getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception error) {
            marker.delete();
            showRuntimeError("Não foi possível preparar o teste Vulkan", safeMessage(error));
            return;
        }
        try {
            startActivity(new Intent(this, android.app.NativeActivity.class));
            setStatus("Teste Vulkan iniciado", "Este teste só confirma a criação da superfície e apresentação de um frame, não o carregamento de Minecraft.");
        } catch (Exception error) {
            marker.delete();
            showRuntimeError("Não foi possível abrir NativeActivity", safeMessage(error));
        }
    }

    private boolean isHorizonNativeLibraryAvailable() {
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) return false;
        ApplicationInfo info = getApplicationInfo();
        File extracted = info.nativeLibraryDir == null ? null
                : new File(info.nativeLibraryDir, "libhorizon_android.so");
        if (extracted != null && extracted.isFile() && extracted.length() > 0) return true;
        List<String> apkPaths = new java.util.ArrayList<>();
        if (info.sourceDir != null) apkPaths.add(info.sourceDir);
        if (info.splitSourceDirs != null) {
            for (String path : info.splitSourceDirs) if (path != null) apkPaths.add(path);
        }
        for (String path : apkPaths) {
            try (ZipFile apk = new ZipFile(path)) {
                ZipEntry entry = apk.getEntry("lib/arm64-v8a/libhorizon_android.so");
                if (entry != null && entry.getSize() > 0) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void showRuntimeError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Entendi", null).show();
    }

    private void clearPendingImport() {
        pendingUri = null;
    }

    private void setBusyUi(boolean importing) {
        updateActionEnablement();
    }

    private void updateActionEnablement() {
        boolean blocked = busy || conversionBusy || downloadBusy;
        if (importFolderButton != null) importFolderButton.setEnabled(!blocked);
        if (importZipButton != null) importZipButton.setEnabled(!blocked);
        if (downloadBaseButton != null) downloadBaseButton.setEnabled(!blocked);
        if (downloadAssetsButton != null) downloadAssetsButton.setEnabled(!blocked);
        if (prepareInputsButton != null) prepareInputsButton.setEnabled(!blocked);
        if (openEngineButton != null) openEngineButton.setEnabled(!blocked);
        if (smokeTestButton != null) smokeTestButton.setEnabled(!blocked);
        if (cancelButton != null) {
            cancelButton.setVisibility(busy || conversionBusy ? View.VISIBLE : View.GONE);
            cancelButton.setEnabled(busy || conversionBusy);
        }
        if (cancelDownloadButton != null) {
            cancelDownloadButton.setVisibility(downloadBusy ? View.VISIBLE : View.GONE);
            cancelDownloadButton.setEnabled(downloadBusy);
        }
        float alpha = blocked ? 0.55f : 1f;
        if (importFolderButton != null) importFolderButton.setAlpha(alpha);
        if (importZipButton != null) importZipButton.setAlpha(alpha);
        if (downloadBaseButton != null) downloadBaseButton.setAlpha(alpha);
        if (downloadAssetsButton != null) downloadAssetsButton.setAlpha(alpha);
        if (prepareInputsButton != null) prepareInputsButton.setAlpha(alpha);
        if (openEngineButton != null) openEngineButton.setAlpha(alpha);
        if (smokeTestButton != null) smokeTestButton.setAlpha(alpha);
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
