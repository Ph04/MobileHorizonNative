# MobileHorizonNative

Launcher Android-first para preparar entradas de Minecraft Java 26.3, validá-las e, gradualmente, substituir o fluxo externo de preparação do Horizon. A camada de compatibilidade mantém o motor nativo isolado da interface e impede que uma cópia de arquivos seja confundida com uma instalação jogável.

> **Estado real do projeto:** download oficial, verificação de integridade, extração inicial dos recursos vanilla, importação de uma saída Horizon completa e um teste Vulkan isolado já existem. A conversão completa que produz todas as tabelas de gameplay/worldgen do Horizon e a autenticação Microsoft/Minecraft ainda não estão implementadas. O botão do cliente não inicia gameplay offline enquanto essas partes faltarem.

## O que já funciona no código

- **Download dos arquivos base oficiais** de Minecraft 26.3: manifesto da versão, cliente JAR, servidor JAR, índice de assets e bibliotecas aplicáveis ao Android. O código verifica tamanho e SHA-1 a partir dos metadados oficiais antes de ativar os arquivos.
- **Download opcional de todos os asset objects**, com três transferências paralelas, deduplicação por hash, progresso por bytes/itens e cancelamento.
- **Serviço Android em primeiro plano** para downloads longos, notificação de andamento/cancelamento e recuperação do estado final quando a interface retorna.
- **Preflight de conversão**: verifica manifesto, hashes dos JARs, bibliotecas e índice de assets; também confere por SHA-1 cada asset object presente.
- **Extração e materialização de entrada vanilla**: extrai `assets/` e `data/` do JAR do cliente e `data/minecraft/` do JAR do servidor para uma área de trabalho versionada; depois reconstitui os nomes oficiais dos asset objects a partir do índice (por exemplo, `minecraft/textures/...`), apenas se o hash do objeto estiver verificado. Os JARs oficiais permanecem intactos. A extração usa pasta temporária, valida caminhos, limita tamanho/quantidade e só ativa a árvore depois de validar os arquivos essenciais. Assets ausentes não são inventados: ficam contabilizados no plano de conversão.
- **Plano de conversão** em `.local/horizon-input/26.3/conversion-plan.json`, acompanhado de `.local/horizon-input/26.3/vanilla/source-input-manifest.json`. O plano registra bibliotecas verificadas, asset objects íntegros/ausentes, nomes de assets materializados e bytes gravados. O estado deixa explícito que essa é uma entrada vanilla, não a saída final do Horizon.
- **Importação SAF de pasta ou ZIP** para `.local/horizon-client/26.3`. Exige manifesto compatível (`schema=7`, `data_version=5023`) e arquivos essenciais, usa staging e mantém rollback quando a importação falha. Limites atuais: 250.000 entradas e 2 GiB descompactados.
- **Renderer nativo ARM64** empacotado pelo workflow: o CI recupera a biblioteca pré-compilada da fonte anterior, confere o SHA-256 e os símbolos esperados antes de incluir `libhorizon_android.so) no APK.
- **Teste Vulkan isolado**: abre NativeActivity e verifica inicialização de Winit/WGPU/Vulkan e apresentação de um frame. O resultado é exibido de volta na interface. Esse teste não é um teste de Minecraft ou de gameplay.
- **Camada de compatibilidade Kotlin**, testes unitários e GitHub Actions para compilar um APK de depuração.

## Compilar e obter o APK de teste

O workflow **Android build, official downloader and Horizon runtime** configura JDK 17, Android SDK 35 e Gradle 8.9, recupera/verifica a biblioteca ARM64, executa testes e compila o APK. Depois de uma execução bem-sucedida, abra a aba Actions e baixe o artifact `mobilehorizon-native-download0-debug`. Ele é um APK de depuração, destinado a testes e não a uma release estável.

Com o ambiente Gradle/Android instalado, o núcleo e o APK também podem ser compilados localmente:

~~~bash
gradle --no-daemon test :app:assembleDebug
~~~

O comando local não baixa automaticamente o runtime nativo que o CI injeta; para testar o renderer, use um APK gerado pelo workflow, no qual a verificação do binário e de seu empacotamento também seja executada.

## Como testar o marco atual

1. Instale o APK de teste. Ele usa um identificador de aplicação separado para não substituir os dados privados dos APKs anteriores.
2. Toque em **Baixar arquivos base oficiais** para baixar manifesto, JARs, índice e bibliotecas. É possível escolher separadamente **Baixar base + todos os assets**, o que pode transferir centenas de MB.
3. Toque em **Preparar recursos vanilla para conversão**. O app valida as entradas e extrai as árvores vanilla para `.local/horizon-input/26.3/vanilla`; o plano registra bibliotecas verificadas, assets ausentes e arquivos extraídos.
4. Para testar o renderer, toque em **Testar renderer Vulkan**. Um resultado PASS significa que a superfície gráfica foi inicializada e apresentou um frame; não significa que o jogo carregou.
5. Para importar uma saída já preparada, selecione a pasta final que contém `horizon-resources.json` na raiz, ou um ZIP dessa pasta. O conteúdo será validado e instalado em `.local/horizon-client/26.3`.

Os downloads são arquivos oficiais e o aplicativo não autentica uma conta nem concede licença. Prossiga apenas se tiver uma licença válida do Minecraft Java Edition.

## O que ainda falta para jogar

- **Portar o exportador Horizon para dentro do Android.** O `horizon-prepare` original gera muitas tabelas derivadas por meio de oráculos Java compilados/executados com JDK 25 (`javac`/`java`). A extração vanilla já está no app, mas ainda não substitui esses oráculos nem produz os arquivos de gameplay/worldgen requeridos.
- **Testar o renderer num dispositivo ARM64 real.** O CI confirma que a biblioteca tem a arquitetura esperada e que está empacotada; somente um teste em aparelho confirma carregamento, superfície gráfica, drivers e comportamento.
- **Integrar login Microsoft/Minecraft e verificar o entitlement de Java Edition.** O cliente Horizon permanece bloqueado na interface até que essa autenticação oficial exista. Não há caminho de gameplay offline habilitado.
- **Validar uma execução real com recursos completos**, depois da conversão e autenticação, antes de chamar o launcher de jogável.

## Organização do repositório

- `app/`: UI nativa, downloader de arquivos oficiais, importação, extração vanilla e verificações do renderer.
- `core/`: modelos e regras da camada de compatibilidade em Kotlin.
- `docs/architecture.md`: fluxo do downloader à runtime.
- `docs/roadmap.md`: estado de cada marco.
- `docs/source-audit-0.9.9.md`: auditoria da fonte antiga e do binário ARM64.
