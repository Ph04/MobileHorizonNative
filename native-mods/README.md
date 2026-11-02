# Pacotes para build dos mods nativos Horizon no Android

Coloque aqui os arquivos originais `.hnmod` (ou `.hmod` que contenham `horizon-native.json`) que deseja compilar para Android ARM64.

**Nunca renomeie `.hmod` para `.hnmod`** e não inclua arquivos que você não tenha revisado. O workflow aplica fontes Rust ao código do Horizon e compila uma biblioteca que será executada dentro do processo do jogo; esse código não fica em sandbox.

## Como compilar

1. Revise todos os arquivos .hnmod/.hmod nativos e adicione os originais à pasta `native-mods/` deste repositório, na branch `feature/fabric-java-bridge-test`.
2. Faça o commit que adiciona os pacotes com a frase exata `[trust-native-mods]` na mensagem. Esse marcador confirma explicitamente que você revisou e confia no código Rust. O workflow roda diretamente nesta branch; não é necessário alterar `main`.
3. Aguarde a validação e compilação ARM64. Essa execução não gera APK.
4. O workflow publica uma release `horizon-native-android-<hash>` com a biblioteca e o manifesto, vinculados às hashes exatas dos arquivos.

O MobileHorizon procura automaticamente a release que corresponde exatamente aos arquivos nativos da instância. Se os bytes do arquivo mudarem, a hash muda e será necessária outra compilação.

O build inicial exige que todos os mods indiquem Minecraft `26.3`, compartilhem a mesma revisão de `horizon-mixins` e sejam compatíveis com os hook points da Nightly selecionada. Mods sem `hooks` precisam fixar a mesma revisão exata do Horizon.

**Limitações deste primeiro compilador Android:** ele aceita injeções Rust, arquivos auxiliares/crates e dados nativos. Ainda rejeita manifestos com `[api]` (traits), `[dependencies]` (resolução automática entre mods) ou `[[post_build]]` (programas executados após a compilação). Esses recursos do launcher de PC ainda não foram portados; a rejeição acontece antes de compilar para evitar um resultado incompleto.

O código compilado executa no processo do jogo, sem sandbox. Não use esse workflow para pacotes cuja origem você não conhece. Não envie JARs Fabric ou mods de conteúdo `.hmod` sem manifesto nativo.
