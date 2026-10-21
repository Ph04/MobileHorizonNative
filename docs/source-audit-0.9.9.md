# Auditoria do pacote-fonte MobileHorizon 0.9.9

O arquivo MobileHorizon-0.9.9-source.zip foi inspecionado para orientar a migração para MobileHorizonNative. O ZIP inclui o código Android/Java 0.9.9, documentação, scripts, uma árvore Rust do Horizon e um binário nativo pré-compilado.

## Achados verificados

- A aplicação anterior é uma Activity Java que concentra download/validação do Minecraft 26.3, importação de pastas e ZIP, exportação de entrada para o preparador externo e testes do NativeActivity/Vulkan.
- O importador anterior já usa ACTION_OPEN_DOCUMENT_TREE e grava no armazenamento privado, mas o fluxo e a UI seguem acoplados à preparação Horizon e à versão de dados específica do projeto.
- O app/build.gradle anterior declara versionName 0.9.9, minSdk 26, compileSdk 35 e Java 17. Parte dos testes estáticos ainda procura a versão 0.9.7; o script tools/test-static-java.sh também falhou neste ambiente ao compilar os stubs Android incompletos, antes de concluir as verificações. Portanto, não tratamos esse script como um build Android bem-sucedido.
- O binário prebuilt/android/arm64-v8a/libhorizon_android.so tem 45.315.408 bytes. O SHA-256 calculado foi 9d564adb67e7aa9b36c5ae66f4e6cf1a74549746216220bc5a1cba235421ab82. file e readelf identificam ELF64/AArch64 compartilhado para Android API 26; os símbolos dinâmicos ANativeActivity_onCreate e android_main estão presentes. Isso valida a estrutura do artefato, não a inicialização do jogo no aparelho.
- O pacote anterior tem hashes e validadores específicos para Minecraft 26.3 / data_version=5023 / schema=7. Eles não devem virar uma exigência universal da camada de compatibilidade.

## Decisão para MobileHorizonNative

A nova base separa a UI Android do núcleo de compatibilidade. O marco inicial inclui importação por SAF de pasta/ZIP, progresso durante a cópia, cancelamento cooperativo, limite de tamanho/itens, bloqueio de caminhos ZIP inseguros, staging e ativação após validação básica.

A biblioteca Horizon não foi copiada para o novo repositório: são aproximadamente 45 MB de binário específico de ARM64 e ainda é um backend opcional. A camada não deve marcar esse backend como compatível antes de verificar ABI e SHA-256. O preparador de recursos nativo continua sendo um marco posterior; importar dados não significa que o motor esteja pronto para executar Minecraft.
