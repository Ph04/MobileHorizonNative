# Pacotes para build dos mods nativos Horizon no Android

Coloque aqui os arquivos originais \`.hnmod\` (ou \`.hmod\` que contenham \`horizon-native.json\`) que deseja compilar para Android ARM64.

**Nunca renomeie \`.hmod\` para \`.hnmod\`** e não inclua arquivos que você não tenha revisado. O workflow aplica fontes Rust ao código do Horizon e compila uma biblioteca que será executada dentro do processo do jogo; esse código não fica em sandbox.

## Como compilar

1. Adicione os arquivos originais na pasta \`native-mods/\` deste repositório, na branch \`feature/fabric-java-bridge-test\`.
2. Abra **Actions → Build Horizon native mods runtime (Android ARM64)**.
3. Escolha **Run workflow** e confirme que revisou/confiou nos pacotes selecionando **I TRUST THESE NATIVE MODS**.
4. Aguarde a compilação ARM64. Ela não gera APK.
5. O workflow publica uma release \`horizon-native-android-<hash>\` com a biblioteca e o manifesto.

O MobileHorizon procura automaticamente a release que corresponde exatamente aos arquivos nativos da instância. Se os bytes do arquivo mudarem, a hash muda e será necessária outra compilação.

O build inicial exige que todos os mods indiquem Minecraft \`26.3\`, compartilhem a mesma revisão de \`horizon-mixins\` e sejam compatíveis com os hook points da Nightly selecionada. Mods sem \`hooks\` precisam fixar a mesma revisão exata do Horizon.

Não use esse workflow para pacotes cuja origem você não conhece. Não envie JARs Fabric ou mods de conteúdo \`.hmod\` sem manifesto nativo.
