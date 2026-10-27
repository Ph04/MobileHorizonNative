package dev.mobilehorizon.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Language preference for the MobileHorizon launcher and its native panels.
 * This deliberately does not change Android's locale or Minecraft's own language setting.
 */
final class UiLanguage {
    private static final String PREFS = "mobilehorizon_interface";
    private static final String KEY_LANGUAGE = "language";
    private static final Map<String, String> PT_TO_EN = new HashMap<>();
    private static final Map<String, String> EN_TO_PT = new HashMap<>();

    static {
        pair("READY", "PRONTO");
        pair("SETUP REQUIRED", "CONFIGURAÇÃO NECESSÁRIA");
        pair("Resources", "Recursos");
        pair("READY TO PLAY", "PRONTO PARA JOGAR");
        pair("PRONTO PARA JOGAR", "READY TO PLAY");
        pair("Status", "Status");
        pair("Gerenciar", "Manage");
        pair("Downloads e preparação", "Downloads & setup");
        pair("Arquivos oficiais do Minecraft 26.3, assets e conversão para o Horizon.", "Official Minecraft 26.3 files, assets, and Horizon preparation.");
        pair("Os arquivos são verificados por tamanho e SHA-1. O download completo de assets pode ocupar centenas de MB.", "Files are verified by size and SHA-1. A complete asset download can use hundreds of MB.");
        pair("Baixar Minecraft (arquivos base)", "Download Minecraft (base files)");
        pair("Baixar base + todos os assets", "Download base + all assets");
        pair("Preparar recursos para jogar", "Prepare game resources");
        pair("A conversão completa e a preparação de entradas mostram o progresso nesta tela e na notificação.", "Full conversion and input preparation show progress here and in the notification.");
        pair("Preparar entradas", "Prepare inputs");
        pair("Conversão completa", "Full conversion");
        pair("Gerenciador de recursos", "Resource manager");
        pair("Importar, inspecionar e organizar recursos já preparados.", "Import, inspect, and organize prepared resources.");
        pair("Gerencie recursos compatíveis com o runtime instalado. A saída preparada do Minecraft fica no armazenamento privado do app.", "Manage resources compatible with the installed runtime. Prepared Minecraft files are stored in the app's private storage.");
        pair("Abrir gerenciador de recursos", "Open resource manager");
        pair("Importar pasta preparada", "Import prepared folder");
        pair("Importar ZIP preparado", "Import prepared ZIP");
        pair("Contas", "Accounts");
        pair("Conta Microsoft opcional; o perfil offline continua disponível.", "A Microsoft account is optional; the offline profile remains available.");
        pair("Nenhuma conta verificada neste dispositivo.", "No account has been verified on this device.");
        pair("Servidores em modo online exigem autenticação oficial. Servidores configurados para modo offline podem aceitar seu nickname local.", "Online-mode servers require official authentication. Servers configured for offline mode may accept your local nickname.");
        pair("Entrar com Microsoft", "Sign in with Microsoft");
        pair("Jogar com conta Microsoft", "Play with Microsoft account");
        pair("Remover conta salva", "Remove saved account");
        pair("Logs e diagnóstico", "Logs & diagnostics");
        pair("Consultar logs locais, testar o renderizador e exportar um pacote de diagnóstico.", "View local logs, test the renderer, and export a diagnostic bundle.");
        pair("Testar Vulkan", "Test Vulkan");
        pair("Abrir logs do Horizon", "Open Horizon logs");
        pair("Exportar diagnóstico", "Export diagnostics");
        pair("Compatibilidade do aparelho", "Device compatibility");
        pair("MobileHorizon • Horizon engine • recursos oficiais verificados", "MobileHorizon • Horizon engine • verified official files");
        pair("INSTÂNCIAS", "INSTANCES");
        pair("NAVEGAÇÃO", "NAVIGATION");
        pair("Início", "Home");
        pair("Downloads", "Downloads");
        pair("Recursos", "Resources");
        pair("Logs", "Logs");
        pair("Atualizações", "Updates");
        pair("Estado do Horizon", "Horizon status");
        pair("Pronto", "Ready");
        pair("Recursos oficiais e motor nativo são gerenciados separadamente.", "Official game files and the native engine are managed separately.");
        pair("Cancelar operação", "Cancel operation");
        pair("Cancelar download", "Cancel download");
        pair("Motor Horizon", "Horizon engine");
        pair("Release de PC acompanhada: ", "Tracked PC release: ");
        pair("Verificar atualizações", "Check for updates");
        pair("Baixar runtime mais recente", "Download latest runtime");
        pair("Atualizando runtime…", "Updating runtime…");
        pair("Baixando runtime…", "Downloading runtime…");
        pair("Recursos preparados para Minecraft ", "Resources prepared for Minecraft ");
        pair(" • saída Horizon encontrada", " • Horizon output found");
        pair("Ainda não preparado • baixe e prepare os recursos antes de jogar", "Not prepared yet • download and prepare resources before playing");
        pair("Substituir importação atual?", "Replace current import?");
        pair("A importação nova só será ativada depois de terminar e passar pela validação inicial. A cópia anterior será preservada se ocorrer uma falha.", "The new import is activated only after it finishes and passes initial validation. The previous copy is preserved if anything fails.");
        pair("Manter atual", "Keep current");
        pair("Substituir", "Replace");
        pair("Baixar arquivos oficiais do Minecraft?", "Download official Minecraft files?");
        pair("Este fluxo baixa arquivos oficiais da Mojang, verifica tamanho e SHA-1, mas não autentica uma conta nem concede uma licença. Prossiga apenas se você tiver uma licença válida de Minecraft Java Edition.", "This process downloads official Mojang files and verifies their size and SHA-1, but it does not authenticate an account or grant a license. Continue only if you have a valid Minecraft Java Edition license.");
        pair("Voltar", "Back");
        pair("Continuar", "Continue");
        pair("Notificações desativadas", "Notifications are disabled");
        pair("A preparação pode continuar, mas o Android talvez não mostre a notificação na área de notificações. Você pode permitir isso nas configurações do app.", "Preparation can continue, but Android may not show a notification. You can allow notifications in the app settings.");
        pair("Continuar mesmo assim", "Continue anyway");
        pair("A conversão pode continuar, mas o progresso e a conclusão talvez não apareçam na área de notificações.", "Conversion can continue, but progress and completion may not appear in notifications.");
        pair("Continuar sem notificações", "Continue without notifications");
        pair("O login pode continuar, mas o Android talvez não mostre a notificação na área de notificações.", "Sign-in can continue, but Android may not show a notification.");
        pair("O download pode continuar em segundo plano, mas o progresso talvez não apareça na área de notificações. Você pode permitir notificações nas configurações do Android.", "The download can continue in the background, but progress may not appear in notifications. You can allow notifications in Android settings.");
        pair("Iniciando serviço de download em segundo plano…", "Starting background download service…");
        pair("Cancelamento solicitado. Arquivos parciais não verificados serão descartados.", "Cancellation requested. Unverified partial files will be discarded.");
        pair("Preparando Java 25 ARM64 e iniciando o exportador completo…", "Preparing Java 25 ARM64 and starting the full exporter…");
        pair("Iniciando serviço de preparação em segundo plano…", "Starting background preparation service…");
        pair("Saída validada: .local/horizon-client/26.3", "Validated output: .local/horizon-client/26.3");
        pair("Nick offline", "Offline nickname");
        pair("Use de 3 a 16 letras, números ou sublinhados.", "Use 3–16 letters, numbers, or underscores.");
        pair("Salvar", "Save");
        pair("Verificando a saída Horizon em segundo plano…", "Verifying Horizon output in the background…");
        pair("Recursos Horizon validados.", "Horizon resources validated.");
        pair("Ativar controles na tela do jogo", "Enable in-game touch controls");
        pair("Layout canhoto (joystick à direita)", "Left-handed layout (joystick on the right)");
        pair("Tamanho dos controles: ", "Control size: ");
        pair("Opacidade: ", "Opacity: ");
        pair("Sensibilidade da câmera: ", "Camera sensitivity: ");
        pair("Controles Touch", "Touch controls");
        pair("Joystick, câmera por arrasto e botões de ação. Há layout canhoto, tamanho, opacidade e sensibilidade ajustáveis. Durante o jogo, toque no ícone superior para ocultar ou mostrar os controles.", "Joystick, swipe camera, and action buttons. Left-handed layout, size, opacity, and sensitivity are adjustable. During gameplay, tap the top icon to show or hide the controls.");
        pair("Entre com a Microsoft", "Sign in with Microsoft");
        pair("Abrir página", "Open page");
        pair("Copiar código", "Copy code");
        pair("Fechar", "Close");
        pair("Remover conta salva?", "Remove saved account?");
        pair("O token de renovação criptografado e os dados públicos da conta serão removidos deste dispositivo.", "The encrypted refresh token and public account data will be removed from this device.");
        pair("Remover", "Remove");
        pair("Logs do Horizon", "Horizon logs");
        pair("Exportar diagnóstico multiplayer?", "Export multiplayer diagnostics?");
        pair("Será criado um ZIP com latest.log, até três logs arquivados recentes, o relatório de falha mais recente e metadados do aparelho/build. O relatório não inclui arquivos de sessão ou tokens de autenticação. O log pode conter o endereço do servidor, nicknames e mensagens de chat; compartilhe somente depois de revisar.", "A ZIP will be created with latest.log, up to three recent archived logs, the latest crash report, and device/build metadata. It excludes session files and authentication tokens. Logs may contain server addresses, nicknames, and chat messages; review them before sharing.");
        pair("Preparar ZIP", "Create ZIP");
        pair("Entendi", "Got it");
        pair("🌐 Idioma", "🌐 Language");
        pair("Painel de texto", "Text input panel");
        pair("DIGITAÇÃO DO MINECRAFT", "MINECRAFT TEXT INPUT");
        pair("Digite abaixo e envie para o campo ativo ou para o chat. O texto aparece no jogo depois do envio.", "Type below and send to the focused field or chat. The text appears in-game after sending.");
        pair("Use o botão Enviar do teclado ou o botão abaixo.", "Use the keyboard's Send button or the button below.");
        pair("Enviar  ↑", "Send  ↑");
        pair("Mensagem, comando ou texto…", "Message, command, or text…");
        pair("Texto do botão (ex.: MAP)", "Button label (e.g. MAP)");
        pair("Tecla (ex.: G, SPACE, F6)", "Key (e.g. G, SPACE, F6)");
        pair("Tecla, ex.: G, SPACE, F6", "Key, e.g. G, SPACE, F6");
        pair("Alternância: ativar/desativar", "Toggle mode: on/off");
        pair("Adicionar botão", "Add button");
        pair("Escolha entre manter a tecla pressionada enquanto segura ou alternar entre ativar e desativar.", "Choose whether to hold the key while pressed or toggle it on and off.");
        pair("Informe um nome curto e uma tecla válida.", "Enter a short label and a valid key.");
        pair("Menu do jogo", "Game overlay menu");
        pair("CONTROLES ATIVOS", "CONTROLS ENABLED");
        pair("CONTROLES OCULTOS", "CONTROLS HIDDEN");
        pair("Personalizar layout", "Customize layout");
        pair("Mover, redimensionar e configurar os botões", "Move, resize, and configure buttons");
        pair("Ajustes dos controles", "Control settings");
        pair("Sensibilidade, opacidade e preferências", "Sensitivity, opacity, and preferences");
        pair("Ocultar controles", "Hide controls");
        pair("Mostrar controles", "Show controls");
        pair("Deixar a tela do jogo mais livre", "Clear more space on the game screen");
        pair("Exibir os botões de toque", "Show touch buttons");
        pair("Fechar jogo", "Close game");
        pair("Encerrar a sessão e voltar à tela inicial", "End the session and return to the launcher");
        pair("Fechar o jogo?", "Close the game?");
        pair("Você voltará à tela inicial do MobileHorizon.", "You will return to the MobileHorizon launcher.");
        pair("MOBILEHORIZON  •  CONTROLES NATIVOS", "MOBILEHORIZON  •  NATIVE CONTROLS");
        pair("Tamanho ", "Size ");
        pair("Tamanho geral ", "Overall size ");
        pair("Opacidade geral ", "Overall opacity ");
        pair("Sensibilidade da câmera ", "Camera sensitivity ");
        pair("Distância de encaixe ", "Snap spacing ");
        pair("Formato: ", "Shape: ");
        pair("Cor: ", "Color: ");
        pair("Corrida automática ao empurrar até a frente", "Auto-sprint when pushing the joystick forward");
        pair("Excluir", "Delete");
        pair("Ajustes dos controles salvos.", "Control settings saved.");
        pair("Botões predefinidos", "Preset buttons");
        pair("Desmarque para excluir da tela. Marque novamente para restaurar. Posições e cores salvas são mantidas.", "Uncheck to remove a button from the screen. Check it again to restore it. Saved positions and colors are kept.");
        pair("Restaurar todos", "Restore all");
        pair("Botões predefinidos restaurados.", "Preset buttons restored.");
        pair("Gerenciar / restaurar botões predefinidos", "Manage / restore preset buttons");
        pair("Botões Clássicos", "Classic buttons");
        pair("Ajustes", "Settings");
        pair("Arraste para mover · toque para editar · + adiciona · ✓ salva", "Drag to move · tap to edit · + adds · ✓ saves");
        pair("Layout dos botões salvo.", "Button layout saved.");
        pair("Não foi possível abrir a personalização sem interromper o jogo.", "Could not open customization without interrupting the game.");
        pair("Informe uma tecla válida para essa função.", "Enter a valid key for this action.");
        pair("Use um texto de até 7 caracteres.", "Use a label of up to 7 characters.");
        pair("Importar arquivos (.zip / .hmod)", "Import files (.zip / .hmod)");
        pair("Pasta de shader", "Shader folder");
        pair("Atualizar lista", "Refresh list");
        pair("Pronto.", "Ready.");
        pair("Organize os recursos instalados e escolha o que será carregado no Minecraft.", "Organize installed resources and choose what will be loaded into Minecraft.");
        pair("Nenhum recurso compatível instalado. Importe um arquivo ou uma pasta de shader pack.", "No compatible resources installed. Import a file or a shader pack folder.");
        pair("Nada instalado aqui ainda. Use Importar arquivos acima para adicionar recursos.", "Nothing installed here yet. Use Import files above to add resources.");
        pair("Instalar", "Install");
        pair("Excluir recurso?", "Delete resource?");
        pair(" será removido do armazenamento do jogo. Essa ação não pode ser desfeita.", " will be removed from game storage. This action cannot be undone.");
        pair("Não foi possível abrir o seletor: ", "Could not open picker: ");
        pair("Não foi possível abrir o seletor de pastas: ", "Could not open folder picker: ");
        pair("Cancelar", "Cancel");
    }

    private UiLanguage() { }

    private static void pair(String portuguese, String english) {
        PT_TO_EN.put(portuguese, english);
        EN_TO_PT.put(english, portuguese);
    }

    static boolean isEnglish(Context context) {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String fallback = Locale.getDefault().getLanguage().equalsIgnoreCase("en") ? "en" : "pt";
        return "en".equals(preferences.getString(KEY_LANGUAGE, fallback));
    }

    static String translate(Context context, String value) {
        if (value == null) return null;
        if (isEnglish(context)) return PT_TO_EN.getOrDefault(value, value);
        return EN_TO_PT.getOrDefault(value, value);
    }

    static String switchLabel(Context context) {
        return "PT ↔ EN";
    }

    static String switchDescription(Context context) {
        return isEnglish(context)
                ? "Interface language: English. Tap to switch to Portuguese."
                : "Idioma da interface: Português. Toque para mudar para English.";
    }

    static void toggle(Activity activity) {
        String next = isEnglish(activity) ? "pt" : "en";
        activity.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, next).apply();
        activity.recreate();
    }
}
