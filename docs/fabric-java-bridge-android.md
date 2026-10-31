# Testar o Fabric Java Bridge no MobileHorizonNative

Este branch integra, como runtime opcional, o bridge do fork [iaxivers/horizon](https://github.com/iaxivers/horizon/tree/java-bridge), fixado no commit `4bd3fee1b05ab89c047b8212d314b4de0912c077`.

## Escopo fixado

- Minecraft Java: `26.3`
- Java no dispositivo: OpenJDK `25` ARM64
- Fabric Loader: `0.20.1`
- Fabric API fornecida pelo bridge: `0.165.0+26.3`
- Runtime: biblioteca nativa Android ARM64 separada, instalada em `filesDir/fabric-horizon-runtime/`

O código Fabric/JVM é o do commit externo fixado. O overlay MobileHorizon mantém a camada Android NativeActivity, tradução touch/mouse e login offline. O patcher pula o socket experimental `08_compatibility_bridge.py`: este perfil usa o runtime Fabric original do fork, não a API `.mhplugin` experimental do launcher.

## Fluxo de teste no app

1. Conclua a preparação oficial de Minecraft 26.3 no Gerenciador de recursos. Isso também instala o Java 25 e prepara o JAR cliente e as bibliotecas que o bridge usa para montar seu classpath.
2. Abra **Instâncias → Módulos de compatibilidade → Instalar/atualizar Fabric Java Bridge**. A biblioteca é baixada de uma release separada, conferida por SHA-256 e ELF ARM64 e ativada sem substituir o runtime Vanilla.
3. Crie uma nova instância e escolha **Fabric Java Bridge**. O perfil só aparece depois que o runtime especial estiver instalado. Não altere a instância padrão.
4. Na instância Fabric, abra **Gerenciar mods Fabric** e importe os JARs que deseja testar. Cada arquivo é copiado para a pasta `mods/` dessa instância e validado pela presença de `fabric.mod.json`. Os mods são ativados por padrão ao importar.
5. Use **Desativar** ou **Ativar** no cartão de cada mod. Os desativados são mantidos em `mods-disabled/`; os ativos permanecem em `mods/`. A alteração vale na próxima inicialização do jogo.
6. Inicie essa instância. Antes de abrir o Horizon, o launcher recria os links privados apenas dos mods ativos em `mods-java/`, fornece o Java 25 e aponta `HORIZON_ROOT` para o workspace de recursos preparados. É permitido iniciar com todos os mods de terceiros desativados.

Não é necessário incluir outro `fabric-api` no pacote: o bridge possui uma versão de Fabric API fixada para Minecraft 26.3. Os mods e suas configurações permanecem separados por instância.

## Isolamento e segurança

- Vanilla continua usando `HorizonRuntimeManager` e o atualizador normal.
- O perfil Fabric usa `FabricBridgeRuntimeManager` e o canal de release `fabric-java-bridge-4bd3fee1`.
- O bridge não é iniciado num perfil Vanilla.
- Os JARs são código executável de terceiros. Importe apenas arquivos de fontes confiáveis.
- O runtime Fabric é experimental. O bridge implementa uma representação Java espelhada do mundo e um cliente Java sem janela; mods que dependam de APIs não mapeadas, renderização GPU própria, mixins fora da cobertura ou recursos ainda ausentes podem não funcionar.
- Este teste não implica compatibilidade geral com qualquer modpack Fabric nem com Forge/NeoForge.
- A Action de runtime compila e publica apenas a biblioteca nativa ARM64 e seu manifesto. Ela não compila nem publica APK.

## Validação de runtime

A Action `.github/workflows/fabric-java-bridge-runtime.yml` fixa o commit externo, aplica o overlay Android, prepara temporariamente os arquivos públicos de Minecraft 26.3 no runner, executa o teste Java do bridge (Fabric Loader, entrypoints e Mixins), compila o membro `mobilehorizon-android` para `aarch64-linux-android` e valida os símbolos NativeActivity/JNI antes da publicação.

Os JARs oficiais usados para compilar a camada de plataforma existem apenas no runner de CI; não são copiados para a release do runtime.
