package dev.mobilehorizon.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small, deterministic in-app language layer for the programmatic Android UI. */
public final class AppLanguage {
    private static final String PREFS = "mobilehorizon_app_settings";
    private static final String KEY_LANGUAGE = "language";
    private static final String SYSTEM = "system";
    private static final Map<String, String> STRINGS = new LinkedHashMap<>();
    private static final Map<String, String> FRAGMENTS = new LinkedHashMap<>();

    static {
        put("Idioma do aplicativo", "App language");
        put("Idioma: Português (Brasil)", "Language: Portuguese (Brazil)");
        put("Language: English (US)", "Language: English (US)");
        put("MOBILEHORIZON  /  RECURSOS", "MOBILEHORIZON  /  RESOURCES");
        put("Gerenciador de recursos", "Resource Manager");
        put("Organize os recursos instalados e escolha o que será carregado no Minecraft.", "Organize installed resources and choose what Minecraft will load.");
        put("Importar arquivos (.zip / .hmod)", "Import files (.zip / .hmod)");
        put("Pasta de shader", "Shader folder");
        put("Atualizar lista", "Refresh list");
        put("Status", "Status");
        put("Pronto.", "Ready.");
        put("Texturas, mods Horizon, data packs e shaders compatíveis. As mudanças entram em vigor ao reiniciar o Minecraft.", "Compatible textures, Horizon mods, data packs, and shaders. Changes take effect after restarting Minecraft.");
        put("Voltar à tela principal", "Back to Home");
        put("Pacotes de textura", "Texture Packs");
        put("ZIPs Java com pack.mcmeta e assets/.", "Java ZIPs containing pack.mcmeta and assets/.");
        put("Mods Horizon", "Horizon Mods");
        put("Arquivos .hmod; a ativação vale no próximo início.", ".hmod files; activation takes effect on the next launch.");
        put("Pacotes de dados", "Data Packs");
        put("Selecione a pasta de um mundo salvo para copiar o pack para datapacks/.", "Choose a saved world's folder to copy the pack into datapacks/.");
        put("Shader packs", "Shader Packs");
        put("Pastas pack.toml/shaders/ e shader ZIPs Java reconhecidos.", "Recognized pack.toml/shaders/ folders and Java shader ZIPs.");
        put("Nada instalado aqui ainda. Use Importar arquivos acima para adicionar recursos.", "Nothing installed here yet. Use Import files above to add resources.");
        put("Nenhum recurso compatível instalado. Importe um arquivo ou uma pasta de shader pack.", "No compatible resources installed. Import a file or a shader-pack folder.");
        put("Ativo", "Active");
        put("Ativar", "Enable");
        put("Por mundo", "Per world");
        put("Remover", "Remove");
        put("Importar skin PNG", "Import skin PNG");
        put("Skins do jogador", "Player Skins");
        put("Coleção local de skins", "Local Skin Collection");
        put("Selecionar como favorita", "Set as favorite");
        put("Favorita", "Favorite");
        put("Remover skin", "Remove skin");
        put("Ativar skin", "Activate skin");
        put("Skin ativa", "Active skin");
        put("Ativa localmente", "Active locally");
        put("✓ Ativa localmente · ", "✓ Active locally · ");
        put("★ Favorita · ", "★ Favorite · ");
        put("Favoritar", "Add to favorites");
        put("Skin removida dos favoritos.", "Skin removed from favorites.");
        put("Skin ativa: ", "Active skin: ");
        put("Ela será carregada na próxima abertura do Minecraft.", "It will be loaded the next time Minecraft starts.");
        put("Importe, ative localmente, favorite ou remova uma skin. A skin ativa será carregada pelo cliente na próxima abertura; isso não altera a skin da conta Microsoft nem força outros clientes do servidor a usar essa textura.", "Import, activate locally, favorite, or remove a skin. The active skin is loaded by this client on next launch; this does not change your Microsoft account skin or force other clients on a server to use this texture.");
        put("Nenhuma skin importada. Adicione um PNG Java de 64×64 ou 64×32 pixels.", "No skins imported. Add a Java skin PNG sized 64×64 or 64×32 pixels.");
        put("PNG inválido. Use exatamente 64×64 ou 64×32 pixels para garantir compatibilidade com o runtime.", "Invalid PNG. Use exactly 64×64 or 64×32 pixels for runtime compatibility.");
        put("Nenhuma skin importada. Adicione um PNG de skin Java (64×64 ou 64×32).", "No skins imported. Add a Java skin PNG (64×64 or 64×32).");
        put("A coleção é salva neste dispositivo. Favoritar não altera automaticamente a skin da conta Microsoft nem a skin fornecida por um servidor.", "This collection is stored on this device. Favoriting a skin does not automatically change your Microsoft account skin or a skin supplied by a server.");
        put("PNG de skin importado: ", "Skin PNG imported: ");
        put("PNG inválido. Use uma textura de skin Java quadrada ou no formato clássico 2:1 (por exemplo, 64×64 ou 64×32).", "Invalid PNG. Use a square Java skin texture or the classic 2:1 layout (for example, 64×64 or 64×32).");
        put("Idioma do aplicativo", "App language");
        put("Cancelar", "Cancel");
        put("Salvar", "Save");
        put("Fechar", "Close");
        put("Excluir", "Delete");
        put("Padrão", "Default");
        put("Botão: ", "Button: ");
        put("Tamanho ", "Size ");
        put("Formato", "Shape");
        put("Retângulo arredondado", "Rounded rectangle");
        put("Círculo / oval", "Circle / oval");
        put("Quadrado", "Square");
        put("Opacidade ", "Opacity ");
        put("Função do botão", "Button action");
        put("Tecla personalizada", "Custom key");
        put("Clique esquerdo", "Left click");
        put("Clique direito", "Right click");
        put("Scroll para cima", "Scroll up");
        put("Scroll para baixo", "Scroll down");
        put("Alternar tecla (toque liga/desliga)", "Toggle key (tap to switch on/off)");
        put("Alternar ação por toque (liga/desliga)", "Toggle action on tap (on/off)");
        put("Alternar tecla por toque (liga/desliga)", "Toggle key on tap (on/off)");
        put("Limiar de corrida ", "Sprint threshold ");
        put("Posição na tela", "On-screen position");
        put("Posição X ", "X position ");
        put("Posição Y ", "Y position ");
        put("Ação do botão", "Button action");
        put("Texto exibido", "Displayed label");
        put("Rótulo do botão (até 7 caracteres)", "Button label (up to 7 characters)");
        put("Ocultar botão", "Hide button");
        put("Alternar pressão: toque para ligar/desligar", "Toggle hold: tap to turn on/off");
        put("Tecla, ex.: G, SPACE, F6", "Key, e.g. G, SPACE, F6");
        put("Texto do botão", "Button label");
        put("Texto do botão (ex.: MAP)", "Button label (e.g. MAP)");
        put("Tecla (ex.: G, SPACE, F6)", "Key (e.g. G, SPACE, F6)");
        put("Adicionar botão", "Add button");
        put("Adicionar", "Add");
        put("Informe um nome curto e uma tecla válida.", "Enter a short label and a valid key.");
        put("Use um texto de até 7 caracteres.", "Use a label of up to 7 characters.");
        put("Informe uma tecla válida para essa função.", "Enter a valid key for this action.");
        put("Personalizar layout", "Customize layout");
        put("Ajustes gerais dos controles", "General control settings");
        put("Ocultar controles", "Hide controls");
        put("Mostrar controles", "Show controls");
        put("Fechar jogo", "Close game");
        put("Configurações do jogo", "Game Settings");
        put("Fechar o jogo?", "Close the game?");
        put("Você voltará à tela inicial do MobileHorizon.", "You will return to the MobileHorizon home screen.");
        put("Restaurar botões", "Restore buttons");
        put("Restaurar botões padrão removidos", "Restore removed default buttons");
        put("Mostrar novamente todos os botões padrão removidos? As posições e tamanhos salvos serão preservados.", "Show all removed default buttons again? Saved positions and sizes will be preserved.");
        put("Restaurar", "Restore");
        put("Botões padrão restaurados.", "Default buttons restored.");
        put("Corrida automática ao empurrar o joystick para frente", "Auto-sprint when pushing the joystick forward");
        put("Ativar controles na tela do jogo", "Enable on-screen game controls");
        put("Layout canhoto (joystick à direita)", "Left-handed layout (joystick on the right)");
        put("Ajustes dos controles", "Control settings");
        put("Tamanho geral ", "Overall size ");
        put("Opacidade geral ", "Overall opacity ");
        put("Sensibilidade da câmera ", "Camera sensitivity ");
        put("Distância de encaixe ", "Snap spacing ");
        put("Ajustes dos controles salvos.", "Control settings saved.");
        put("Teclado do Minecraft", "Minecraft Keyboard");
        put("Texto para o campo selecionado ou mensagem", "Text for the selected field or chat message");
        put("Aplicar / enviar", "Apply / send");
        put("Arraste os controles; toque em um para configurar. ✓ salva.", "Drag controls; tap one to configure. ✓ saves.");
        put("LAYOUT DOS CONTROLES  ·  Arraste para mover · toque para editar", "CONTROL LAYOUT  ·  Drag to move · tap to edit");
        put("⚙  Ajustes", "⚙  Settings");
        put("Controles", "Controls");
        put("▶  Jogar", "▶  Play");
        put("PRONTO PARA JOGAR", "READY TO PLAY");
        put("Estado do Horizon", "Horizon Status");
        put("Pronto", "Ready");
        put("Recursos oficiais e motor nativo são gerenciados separadamente.", "Official game resources and the native engine are managed separately.");
        put("Cancelar operação", "Cancel operation");
        put("Cancelar download", "Cancel download");
        put("Resources", "Resources");
        put("Gerenciar", "Manage");
        put("Minecraft folder", "Minecraft folder");
        put("Diretório privado: files/minecraft • Downloads oficiais verificados", "Private directory: files/minecraft • Official downloads verified");
        put("Multiplayer", "Multiplayer");
        put("O menu de servidores fica dentro do Horizon. O perfil offline funciona em servidores que aceitam jogadores sem autenticação oficial.", "The server list is inside Horizon. The offline profile works on servers that accept players without official authentication.");
        put("Abrir Horizon", "Open Horizon");
        put("Motor Horizon", "Horizon Engine");
        put("Verificar atualizações", "Check for updates");
        put("Verificando a versão mais recente do Horizon...", "Checking for the latest Horizon version...");
        put("Downloads e preparação", "Downloads and Setup");
        put("Arquivos oficiais do Minecraft 26.3, assets e conversão para o Horizon.", "Official Minecraft 26.3 files, assets, and conversion for Horizon.");
        put("Os arquivos são verificados por tamanho e SHA-1. O download completo de assets pode ocupar centenas de MB.", "Files are verified by size and SHA-1. A full asset download can use hundreds of MB.");
        put("Baixar Minecraft (arquivos base)", "Download Minecraft (base files)");
        put("Baixar base + todos os assets", "Download base + all assets");
        put("Preparar recursos para jogar", "Prepare resources to play");
        put("A conversão completa e a preparação de entradas mostram o progresso nesta tela e na notificação.", "Full conversion and input preparation show progress here and in the notification.");
        put("Preparar entradas", "Prepare inputs");
        put("Conversão completa", "Full conversion");
        put("Gerenciador de recursos", "Resource Manager");
        put("Importar pasta preparada", "Import prepared folder");
        put("Importar ZIP preparado", "Import prepared ZIP");
        put("Contas", "Accounts");
        put("Nenhuma conta verificada neste dispositivo.", "No account verified on this device.");
        put("Servidores em modo online exigem autenticação oficial. Servidores configurados para modo offline podem aceitar seu nickname local.", "Online-mode servers require official authentication. Servers configured for offline mode may accept your local nickname.");
        put("Entrar com Microsoft", "Sign in with Microsoft");
        put("Jogar com conta Microsoft", "Play with Microsoft account");
        put("Remover conta salva", "Remove saved account");
        put("Logs e diagnóstico", "Logs and Diagnostics");
        put("Consultar logs locais, testar o renderizador e exportar um pacote de diagnóstico.", "View local logs, test the renderer, and export a diagnostic bundle.");
        put("Testar Vulkan", "Test Vulkan");
        put("Abrir logs do Horizon", "Open Horizon logs");
        put("Os logs podem conter endereços de servidores, nicknames e mensagens. Revise o arquivo antes de compartilhar.", "Logs may contain server addresses, nicknames, and messages. Review the file before sharing.");
        put("Exportar diagnóstico", "Export diagnostics");
        put("Compatibilidade do aparelho", "Device Compatibility");
        put("MobileHorizon • Horizon engine • recursos oficiais verificados", "MobileHorizon • Horizon engine • verified official resources");
        put("Pronto para Minecraft ", "Prepared for Minecraft ");
        put(" • saída Horizon encontrada", " • Horizon output found");
        put("Ainda não preparado • baixe e prepare os recursos antes de jogar", "Not prepared yet • download and prepare resources before playing");
        put("Ative", "Enable");
        put("Idioma", "Language");
        put("Nome do jogador offline", "Offline player name");
        put("Nick: ", "Name: ");
        put("Ajustes", "Settings");
        put("Importar", "Import");
        put("Pasta", "Folder");
        put("Falha", "Failed");
        put("Não foi possível abrir o seletor: ", "Could not open picker: ");
        put("Não foi possível abrir o seletor de pastas: ", "Could not open folder picker: ");
        put("Importação concluída: ", "Import completed: ");
        put(" arquivo(s).", " file(s).");
        put("Importando ", "Importing ");
        put("A mudança será aplicada quando o Minecraft iniciar novamente.", "This change will take effect the next time Minecraft starts.");
        put(" ativado", " enabled");
        put(" desativado", " disabled");
        put("Pacote de textura Java; aplica na próxima abertura.", "Java texture pack; applied at next launch.");
        put("Manifest inválido; não pode ser ativado.", "Invalid manifest; cannot be enabled.");
        put("Pasta de shader Horizon/Java.", "Horizon/Java shader folder.");
        put("Shader pack Java ZIP.", "Java shader pack ZIP.");
        put("Data pack em ZIP. Instale-o em datapacks/ de um mundo salvo.", "ZIP data pack. Install it in datapacks/ inside a saved world.");
        put("Textura", "Texture");
        put("Ativar controles", "Enable controls");

        // These phrases also appear inside dynamic status messages.
        fragment("A mudança será aplicada quando o Minecraft iniciar novamente.", "This change will take effect the next time Minecraft starts.");
        fragment("O Android não conseguiu abrir o arquivo.", "Android could not open the file.");
        fragment("Não foi possível abrir o seletor:", "Could not open picker:");
        fragment("O recurso ultrapassa o limite de 512 MiB.", "The resource exceeds the 512 MiB limit.");
        fragment("Feche e reabra o mundo para validar o conteúdo.", "Close and reopen the world to validate the content.");
        fragment("Nome de arquivo inseguro.", "Unsafe file name.");
        fragment("Skin ativa: ", "Active skin: ");
        fragment("Skin favorita: ", "Favorite skin: ");
        fragment("Skin removida: ", "Skin removed: ");
        fragment("Falha ao remover a skin: ", "Failed to remove skin: ");
        fragment("Não foi possível ativar a skin: ", "Could not activate skin: ");
        fragment("Não foi possível atualizar o favorito: ", "Could not update favorite: ");
        fragment("Não foi possível configurar a skin local", "Could not configure the local skin");
    }

    private AppLanguage() { }

    private static void put(String pt, String en) { STRINGS.put(pt, en); }
    private static void fragment(String pt, String en) { FRAGMENTS.put(pt, en); }

    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_LANGUAGE, SYSTEM);
        if (SYSTEM.equals(value)) {
            Locale current = context.getResources().getConfiguration().getLocales().isEmpty()
                    ? Locale.getDefault()
                    : context.getResources().getConfiguration().getLocales().get(0);
            return "en".equalsIgnoreCase(current.getLanguage()) ? "en-US" : "pt-BR";
        }
        return "en-US".equals(value) ? "en-US" : "pt-BR";
    }

    public static boolean isEnglish(Context context) {
        return "en-US".equals(getLanguage(context));
    }

    public static void setLanguage(Context context, String languageTag) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, "en-US".equals(languageTag) ? "en-US" : "pt-BR")
                .apply();
    }

    public static String languageLabel(Context context) {
        return isEnglish(context) ? "Language: English (US)" : "Idioma: Português (Brasil)";
    }

    public static String translate(Context context, String value) {
        if (value == null || value.isEmpty() || !isEnglish(context)) return value;
        String exact = STRINGS.get(value);
        if (exact != null) return exact;
        String translated = value;
        for (Map.Entry<String, String> entry : FRAGMENTS.entrySet()) {
            translated = translated.replace(entry.getKey(), entry.getValue());
        }
        if (translated.startsWith("Tamanho dos controles: ")) {
            return "Control size: " + translated.substring("Tamanho dos controles: ".length());
        }
        if (translated.startsWith("Opacidade: ")) {
            return "Opacity: " + translated.substring("Opacidade: ".length());
        }
        if (translated.startsWith("Tamanho geral ")) {
            return "Overall size " + translated.substring("Tamanho geral ".length());
        }
        if (translated.startsWith("Opacidade geral ")) {
            return "Overall opacity " + translated.substring("Opacidade geral ".length());
        }
        if (translated.startsWith("Sensibilidade da câmera ")) {
            return "Camera sensitivity " + translated.substring("Sensibilidade da câmera ".length());
        }
        if (translated.startsWith("Distância de encaixe ")) {
            return "Snap spacing " + translated.substring("Distância de encaixe ".length());
        }
        if (translated.startsWith("Tamanho ")) {
            return "Size " + translated.substring("Tamanho ".length());
        }
        if (translated.startsWith("Opacidade ")) {
            return "Opacity " + translated.substring("Opacidade ".length());
        }
        if (translated.startsWith("Botão: ")) {
            return "Button: " + translated.substring("Botão: ".length());
        }
        if (translated.startsWith("Nick: ")) {
            return "Name: " + translated.substring("Nick: ".length());
        }
        if (translated.startsWith("Importando ")) {
            return "Importing " + translated.substring("Importando ".length());
        }
        if (translated.startsWith("Importação concluída: ")) {
            return "Import completed: " + translated.substring("Importação concluída: ".length());
        }
        if (translated.startsWith("PNG de skin importado: ")) {
            return "Skin PNG imported: " + translated.substring("PNG de skin importado: ".length());
        }
        return translated;
    }
}
