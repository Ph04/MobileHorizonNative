# Arquitetura Android-first

## Fluxo implementado e fluxo final

O marco atual implementa:

UI Android → Importador → Staging no armazenamento privado → Validação básica → Ativação

O fluxo alvo é:

UI → Job Coordinator → Compatibility Layer → Preparation Backend → Validator → Importer

O app não executa I/O pesado na thread principal. A importação reporta a fase atual, a contagem de arquivos e os bytes processados. A barra é indeterminada enquanto a origem ZIP não tem total extraído confiável; não é inventada uma porcentagem.

## 1. Camada de compatibilidade

Os modelos e o verificador vivem no módulo core, sem depender da UI Android:

- DeviceCapabilities: SDK, ABIs, disponibilidade do SAF e memória disponível quando conhecida.
- BackendDescriptor: id, versão, ABIs aceitas, SDK mínimo, capacidades e SHA-256 opcional.
- CompatibilityReport: status, problemas codificados e indicação de bloqueio.
- PreparationBackend: interface para implementação de preparação assíncrona com progresso.
- PreparationProgress / PreparationResult: progresso e resultado estruturados.

O verificador aplica os requisitos declarados pelo backend. O SAF só é bloqueador para backends que declaram IMPORT_OUTPUT; uma tarefa headless não deve ser rejeitada só porque não usa o seletor de documentos. Para integrações Horizon, o hash declarado é obrigatório e precisa ter formato SHA-256 válido. Isso valida o descritor, não verifica por si só os bytes do arquivo — essa comparação cabe ao adaptador ao carregar o artefato.

## 2. Importação segura

- A origem vem de ACTION_OPEN_DOCUMENT_TREE ou ACTION_OPEN_DOCUMENT.
- Os arquivos são copiados para prepared-resources.importing, dentro do armazenamento privado do app.
- ZIPs não podem escrever fora da área temporária, usar caminhos absolutos, repetir caminhos nem exceder os limites definidos no importador.
- A pasta ativa só é substituída quando a extração termina e a validação básica passa. A versão anterior é guardada temporariamente para rollback.
- Se existir horizon-resources.json na raiz, o JSON e os campos numéricos schema e data_version são verificados. Não é imposta uma versão global específica à importação genérica.
- Sem manifesto, a UI deixa claro que os arquivos foram importados, mas não validados para um motor específico.

## 3. Backends isolados

O backend de importação é portátil e não exige a biblioteca Horizon. O futuro backend de preparação Android deve implementar PreparationBackend. O adaptador Horizon é opcional e só pode ficar disponível quando ABI, hash binário, dependências, permissões e inicialização no dispositivo forem verificados. Um backend incompatível deve ficar desativado com motivo legível, sem bloquear o restante da app.

## 4. Limites de compatibilidade

SDK e ABI informam compatibilidade estrutural, não garantem desempenho. A memória disponível pode ser observada, mas precisa de um requisito explícito do backend antes de se tornar um bloqueio automático. Vulkan, drivers GPU, lifecycle nativo e carregamento do jogo exigem testes reais em ARM64; a verificação estática de uma biblioteca não substitui esses testes.

## 5. Testabilidade

Os testes de core cobrem SDK/ABI/SAF, requisitos do adaptador Horizon, formatos de hash e progresso. A CI também tenta compilar o APK. Ainda faltam testes instrumentados de SAF/importação, cancelamento sob carga, recuperação após encerramento do processo e testes em aparelhos físicos.
