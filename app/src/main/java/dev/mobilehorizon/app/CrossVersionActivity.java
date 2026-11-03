package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Built-in controls for translating older Minecraft Java protocols through ViaProxy. */
public final class CrossVersionActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView javaStatus;
    private TextView proxyStatus;
    private TextView accountStatus;
    private TextView deviceCode;
    private TextView deviceCodeHint;
    private LinearLayout overrideList;
    private EditText serverAddress;
    private Spinner targetVersion;
    private Button downloadButton;
    private Button signInButton;
    private Button deleteButton;
    private CheckBox enabledCheck;
    private CheckBox alwaysCheck;
    private CheckBox offlineAuthCheck;
    private boolean busy;
    private boolean receiverRegistered;

    private final BroadcastReceiver signInReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent event) {
            String action = event == null ? "" : event.getAction();
            if (CrossVersionService.ACTION_PROGRESS.equals(action)) {
                String stage = event.getStringExtra("stage");
                String detail = event.getStringExtra("detail");
                setStatus(stage == null ? "Autenticação em andamento" : stage,
                        detail == null ? "" : detail);
            } else if (CrossVersionService.ACTION_CODE.equals(action)) {
                showDeviceCode(event.getStringExtra("code"));
                setStatus("Aguardando aprovação na Microsoft", "Digite o código exibido abaixo em microsoft.com/link.");
            } else if (CrossVersionService.ACTION_FINISHED.equals(action)) {
                String message = event.getStringExtra("message");
                setStatus(Boolean.TRUE.equals(event.getBooleanExtra("success", false))
                                ? "Conta Microsoft adicionada" : "Autenticação finalizada",
                        message == null ? "" : message);
                refreshAccountStatus();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LauncherButtonStyle.install(this);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        refreshState();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090911);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(30));
        scroll.addView(page);
        setContentView(scroll);

        page.addView(text("MOBILEHORIZON  /  REDE", 11, 0xff9e88ff, true), lp(-1, -2));
        page.addView(text("CrossVersion", 27, 0xfff0ecff, true), top(-1, -2, 5));
        page.addView(text(
                "Entre em servidores Minecraft Java de versões anteriores. O Horizon continua falando 26.3 com o ViaProxy local; o proxy traduz o protocolo para a versão do servidor.",
                13, 0xffb9b3d1, false), top(-1, -2, 8));
        page.addView(button("Voltar", false, v -> finish()), top(-1, 48, 12));

        LinearLayout main = card();
        main.addView(text("Ativação", 16, 0xfff0ecff, true), lp(-1, -2));
        enabledCheck = checkBox("Ativar tradução de protocolo");
        enabledCheck.setOnCheckedChangeListener((view, checked) -> {
            try {
                CrossVersionManager.setEnabled(this, checked);
                setStatus(checked ? "CrossVersion ativado" : "CrossVersion desativado",
                        checked ? "Servidores em outras versões serão verificados antes da conexão."
                                : "Novas conexões voltarão a usar o protocolo normal do Horizon.");
                if (checked) prepareViaProxy();
            } catch (Exception error) {
                setStatus("Não foi possível salvar a configuração", message(error));
                refreshState();
            }
        });
        main.addView(enabledCheck, top(-1, 50, 5));
        alwaysCheck = checkBox("Usar ViaProxy em todos os servidores (diagnóstico)");
        alwaysCheck.setOnCheckedChangeListener((view, checked) -> {
            try {
                CrossVersionManager.setAlways(this, checked);
            } catch (Exception error) {
                setStatus("Não foi possível salvar a configuração", message(error));
                refreshState();
            }
        });
        main.addView(alwaysCheck, top(-1, 50, 2));
        status = text("", 12, 0xffaaa2c6, false);
        main.addView(status, top(-1, -2, 7));
        page.addView(main, top(-1, -2, 12));

        LinearLayout runtime = card();
        runtime.addView(text("Runtime e ViaProxy", 16, 0xfff0ecff, true), lp(-1, -2));
        javaStatus = text("", 12, 0xffaaa2c6, false);
        runtime.addView(javaStatus, top(-1, -2, 7));
        proxyStatus = text("", 12, 0xffaaa2c6, false);
        runtime.addView(proxyStatus, top(-1, -2, 5));
        downloadButton = button("Baixar e verificar ViaProxy 3.4.14", true, v -> prepareViaProxy());
        runtime.addView(downloadButton, top(-1, 48, 9));
        page.addView(runtime, top(-1, -2, 12));

        LinearLayout account = card();
        account.addView(text("Autenticação Microsoft do ViaProxy", 16, 0xfff0ecff, true), lp(-1, -2));
        account.addView(text(
                "Para servidores online-mode, adicione uma conta diretamente ao ViaProxy pelo código de dispositivo. O token fica no saves.json do ViaProxy; a sessão do Minecraft e os códigos de autenticação do Horizon não são compartilhados.",
                12, 0xffb9b3d1, false), top(-1, -2, 6));
        offlineAuthCheck = checkBox("Forçar autenticação offline no ViaProxy");
        offlineAuthCheck.setOnCheckedChangeListener((view, checked) -> {
            try {
                CrossVersionManager.setOfflineAuth(this, checked);
                setStatus(checked ? "Modo offline forçado" : "Autenticação automática ativada",
                        checked
                                ? "O ViaProxy ignorará qualquer conta Microsoft salva. Servidores online-mode ainda exigem uma conta válida."
                                : "Se houver uma conta Microsoft vinculada ao ViaProxy, ela será usada automaticamente.");
                refreshAccountStatus();
            } catch (Exception error) {
                setStatus("Não foi possível salvar o modo de autenticação", message(error));
                refreshState();
            }
        });
        account.addView(offlineAuthCheck, top(-1, 50, 7));
        account.addView(text(
                "Use esta opção em servidores offline-mode. Ela não remove a conta salva; apenas impede que o ViaProxy a utilize.",
                11, 0xffaaa2c6, false), top(-1, -2, 2));
        accountStatus = text("", 12, 0xffaaa2c6, false);
        account.addView(accountStatus, top(-1, -2, 8));
        signInButton = button("Vincular conta Microsoft", false, v -> startSignIn());
        account.addView(signInButton, top(-1, 48, 7));
        deviceCode = text("", 23, 0xffe5dcff, true);
        deviceCode.setTypeface(android.graphics.Typeface.MONOSPACE);
        account.addView(deviceCode, top(-1, -2, 8));
        deviceCodeHint = text("", 11, 0xffaaa2c6, false);
        account.addView(deviceCodeHint, top(-1, -2, 3));
        account.addView(button("Abrir microsoft.com/link", false, v -> openMicrosoftLink()),
                top(-1, 46, 6));
        page.addView(account, top(-1, -2, 12));

        LinearLayout server = card();
        server.addView(text("Versão por servidor", 16, 0xfff0ecff, true), lp(-1, -2));
        server.addView(text(
                "Normalmente o ViaProxy detecta a versão pelo status do servidor. Para redes BungeeCord/Velocity que anunciam uma versão diferente da dos lobbies, configure aqui a versão de destino.",
                12, 0xffb9b3d1, false), top(-1, -2, 6));
        serverAddress = new EditText(this);
        serverAddress.setSingleLine(true);
        serverAddress.setHint("endereco.exemplo:25565");
        serverAddress.setHintTextColor(0xff817a9e);
        serverAddress.setTextColor(0xfff0ecff);
        serverAddress.setTextSize(14);
        serverAddress.setPadding(dp(10), dp(8), dp(10), dp(8));
        serverAddress.setBackground(fieldBackground());
        server.addView(serverAddress, top(-1, 48, 9));

        targetVersion = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, CrossVersionManager.targetVersions());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetVersion.setAdapter(adapter);
        server.addView(targetVersion, top(-1, 48, 5));
        server.addView(button("Salvar versão para este servidor", false, v -> saveServerVersion()),
                top(-1, 46, 7));
        server.addView(text("Versões configuradas", 13, 0xffd5c9f4, true), top(-1, -2, 12));
        overrideList = new LinearLayout(this);
        overrideList.setOrientation(LinearLayout.VERTICAL);
        server.addView(overrideList, top(-1, -2, 4));
        page.addView(server, top(-1, -2, 12));

        LinearLayout maintenance = card();
        maintenance.addView(text("Manutenção", 15, 0xfff0ecff, true), lp(-1, -2));
        maintenance.addView(text(
                "Excluir apenas o JAR baixado do ViaProxy. A conta Microsoft e o saves.json serão preservados. Feche o jogo antes de remover o arquivo.",
                11, 0xffaaa2c6, false), top(-1, -2, 5));
        deleteButton = button("Excluir download do ViaProxy", false, v -> confirmDelete());
        maintenance.addView(deleteButton, top(-1, 46, 7));
        page.addView(maintenance, top(-1, -2, 12));

        page.addView(text(
                "Compatibilidade: ViaProxy 3.4.14 · Minecraft 26.3 · Java 25 ARM64. A tradução não contorna plugins anticheat nem transforma o Horizon em cliente Bedrock.",
                11, 0xff817a9e, false), top(-1, -2, 12));
    }

    private void refreshState() {
        try {
            JSONObject config = CrossVersionManager.readConfig(this);
            enabledCheck.setChecked(config.optBoolean("enabled", true));
            alwaysCheck.setChecked(config.optBoolean("always", false));
            offlineAuthCheck.setChecked(config.optBoolean("offline_auth", false));
        } catch (Exception error) {
            setStatus("Configuração inválida", message(error));
        }
        refreshJavaStatus();
        refreshProxyStatus();
        refreshAccountStatus();
        refreshOverrides();
    }

    private void refreshJavaStatus() {
        try {
            CrossVersionManager.JavaRuntime runtime = CrossVersionManager.findJavaRuntime(this);
            javaStatus.setText("Java pronto: " + runtime.provider
                    + "\nExecutável: " + runtime.java.getAbsolutePath()
                    + "\nViaProxy roda em processo separado, com heap limitado a 256 MiB.");
        } catch (Exception error) {
            javaStatus.setText("Java ainda não preparado. Execute a preparação completa dos recursos do Horizon ou instale o JRE Pojav/Mojo em Configurações.\n"
                    + message(error));
        }
    }

    private void refreshProxyStatus() {
        boolean ready = CrossVersionManager.isViaProxyReady(this);
        proxyStatus.setText(ready
                ? "ViaProxy " + CrossVersionManager.VIA_PROXY_VERSION + " instalado e SHA-256 verificado."
                : "ViaProxy ainda não foi baixado. A primeira preparação exige internet.");
        if (downloadButton != null && !busy) {
            downloadButton.setEnabled(!ready);
            downloadButton.setText(ready ? "ViaProxy verificado" : "Baixar e verificar ViaProxy 3.4.14");
        }
    }

    private void refreshAccountStatus() {
        boolean signedIn = CrossVersionManager.hasMicrosoftAccount(this);
        boolean forceOffline = false;
        try {
            forceOffline = CrossVersionManager.readConfig(this).optBoolean("offline_auth", false);
        } catch (Exception ignored) { }
        accountStatus.setText(forceOffline
                ? "Modo offline forçado: qualquer conta Microsoft salva será ignorada. Servidores online-mode exigem autenticação premium."
                : signedIn
                ? "Conta Microsoft vinculada ao ViaProxy; ela será usada automaticamente quando o servidor exigir autenticação online."
                : "Sem conta Microsoft no ViaProxy: o motor usa auth-method NONE, e servidores offline-mode decidem se aceitam o perfil.");
        if (signInButton != null) {
            signInButton.setEnabled(!signedIn);
            signInButton.setText(signedIn ? "Conta Microsoft vinculada" : "Vincular conta Microsoft");
        }
    }

    private void refreshOverrides() {
        if (overrideList == null) return;
        overrideList.removeAllViews();
        try {
            List<String> entries = CrossVersionManager.serverOverrides(this);
            if (entries.isEmpty()) {
                overrideList.addView(text("Nenhum servidor com versão manual. O padrão é detecção automática.",
                        11, 0xffaaa2c6, false), lp(-1, -2));
                return;
            }
            for (String entry : entries) {
                final String address = entry.substring(0, entry.lastIndexOf(" → "));
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.addView(text(entry, 12, 0xffd5c9f4, false),
                        new LinearLayout.LayoutParams(0, -2, 1f));
                row.addView(button("Auto", false, v -> {
                    try {
                        CrossVersionManager.setServerVersion(this, address, "auto");
                        refreshOverrides();
                    } catch (Exception error) {
                        setStatus("Não foi possível remover a versão manual", message(error));
                    }
                }), lp(dp(72), 38));
                overrideList.addView(row, top(-1, -2, 4));
            }
        } catch (Exception error) {
            overrideList.addView(text(message(error), 11, 0xffff9e9e, false), lp(-1, -2));
        }
    }

    private void prepareViaProxy() {
        if (busy || CrossVersionManager.isViaProxyReady(this)) {
            refreshProxyStatus();
            return;
        }
        busy = true;
        setBusy(true);
        setStatus("Baixando ViaProxy " + CrossVersionManager.VIA_PROXY_VERSION, "Validando o SHA-256 do arquivo oficial.");
        worker.execute(() -> {
            String result = "";
            boolean success = false;
            try {
                CrossVersionManager.ensureViaProxyDownloaded(this,
                        (stage, completed, total) -> runOnUiThread(() ->
                                setStatus(stage, total > 0 ? completed + " / " + total + " bytes" : "")));
                success = true;
                result = "ViaProxy pronto. O motor usará este arquivo na próxima conexão que precisar de tradução.";
            } catch (Exception error) {
                result = message(error);
            }
            final boolean ok = success;
            final String finalResult = result;
            runOnUiThread(() -> {
                busy = false;
                setBusy(false);
                setStatus(ok ? "ViaProxy instalado" : "Falha ao preparar ViaProxy", finalResult);
                refreshProxyStatus();
                refreshJavaStatus();
            });
        });
    }

    private void startSignIn() {
        if (busy) return;
        if (!CrossVersionManager.isViaProxyReady(this)) {
            setStatus("Prepare o ViaProxy primeiro", "");
            prepareViaProxy();
            return;
        }
        if (CrossVersionManager.hasMicrosoftAccount(this)) {
            refreshAccountStatus();
            return;
        }
        Intent service = new Intent(this, CrossVersionService.class)
                .setAction(CrossVersionService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
        else startService(service);
        deviceCode.setText("");
        deviceCodeHint.setText("");
        setStatus("Iniciando autenticação Microsoft", "O código aparecerá aqui quando o ViaProxy o receber.");
    }

    private void showDeviceCode(String code) {
        runOnUiThread(() -> {
            deviceCode.setText(code == null ? "" : code);
            deviceCodeHint.setText("Digite este código em microsoft.com/link. Não compartilhe o código com outras pessoas.");
        });
    }

    private void openMicrosoftLink() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.microsoft.com/link"));
        try {
            startActivity(intent);
        } catch (Exception error) {
            setStatus("Não foi possível abrir o navegador", message(error));
        }
    }

    private void saveServerVersion() {
        try {
            String address = CrossVersionManager.normalizeAddress(
                    serverAddress.getText() == null ? "" : serverAddress.getText().toString());
            String version = String.valueOf(targetVersion.getSelectedItem());
            CrossVersionManager.setServerVersion(this, address, version);
            serverAddress.setText("");
            setStatus("Versão do servidor salva", address + " → " + version);
            refreshOverrides();
        } catch (Exception error) {
            setStatus("Não foi possível salvar a versão", message(error));
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Excluir o ViaProxy baixado?")
                .setMessage("Somente o JAR do ViaProxy será removido. O saves.json, a conta Microsoft e as configurações serão preservados. Feche o jogo antes de continuar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Excluir JAR", (dialog, which) -> {
                    try {
                        CrossVersionManager.deleteDownloadedJars(this);
                        setStatus("Download removido", "A próxima preparação baixará e verificará o ViaProxy novamente.");
                        refreshProxyStatus();
                    } catch (Exception error) {
                        setStatus("Não foi possível excluir o ViaProxy", message(error));
                    }
                }).show();
    }

    private void setBusy(boolean value) {
        if (downloadButton != null) downloadButton.setEnabled(!value);
        if (enabledCheck != null) enabledCheck.setEnabled(!value);
        if (alwaysCheck != null) alwaysCheck.setEnabled(!value);
        if (offlineAuthCheck != null) offlineAuthCheck.setEnabled(!value);
        if (signInButton != null) signInButton.setEnabled(!value
                && !CrossVersionManager.hasMicrosoftAccount(this));
        if (deleteButton != null) deleteButton.setEnabled(!value);
    }

    private void setStatus(String title, String detail) {
        if (status == null) return;
        status.setText((title == null ? "" : title)
                + (detail == null || detail.isEmpty() ? "" : "\n" + detail));
    }

    private CheckBox checkBox(String label) {
        CheckBox check = new CheckBox(this);
        check.setText(UiLanguage.translate(this, label));
        check.setTextColor(0xffeee9ff);
        check.setTextSize(13);
        check.setButtonTintList(android.content.res.ColorStateList.valueOf(0xff9e88ff));
        return check;
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(13), dp(12), dp(13), dp(13));
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(0xff171421);
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), 0xff39314f);
        layout.setBackground(background);
        return layout;
    }

    private Button button(String label, boolean primary, View.OnClickListener action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(UiLanguage.translate(this, label));
        b.setTextSize(12);
        b.setTextColor(0xffffffff);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(primary ? 0xff5b3fd6 : 0xff2b2641);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), primary ? 0xff8a74ff : 0xff3d3560);
        b.setBackground(bg);
        b.setOnClickListener(action);
        return b;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(UiLanguage.translate(this, value));
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.DEFAULT,
                bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        return view;
    }

    private android.graphics.drawable.Drawable fieldBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xff100e18);
        bg.setCornerRadius(dp(5));
        bg.setStroke(dp(1), 0xff39314f);
        return bg;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams top(int width, int height, int margin) {
        LinearLayout.LayoutParams p = lp(width, height);
        p.topMargin = dp(margin);
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String message(Throwable error) {
        if (error == null) return "Erro desconhecido.";
        String value = error.getMessage();
        return value == null || value.trim().isEmpty()
                ? error.getClass().getSimpleName() : value;
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter();
        filter.addAction(CrossVersionService.ACTION_PROGRESS);
        filter.addAction(CrossVersionService.ACTION_CODE);
        filter.addAction(CrossVersionService.ACTION_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(signInReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(signInReceiver, filter);
        }
        receiverRegistered = true;
        android.content.SharedPreferences signInState =
                getSharedPreferences("crossversion_state", MODE_PRIVATE);
        String savedCode = signInState.getString("sign_in_code", "");
        if (savedCode != null && !savedCode.isEmpty()
                && !"done".equals(signInState.getString("sign_in_state", ""))) {
            showDeviceCode(savedCode);
            deviceCodeHint.setText("Este código ainda está salvo para a autenticação em andamento. Não o compartilhe.");
        }
        Intent query = new Intent(this, CrossVersionService.class)
                .setAction(CrossVersionService.ACTION_QUERY);
        startService(query);
        refreshState();
    }

    @Override protected void onStop() {
        if (receiverRegistered) {
            try { unregisterReceiver(signInReceiver); }
            catch (Exception ignored) { }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
