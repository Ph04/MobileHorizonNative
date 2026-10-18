# Arquitetura Android-first

## Fluxo de alto nível

`UI → Job Coordinator → Compatibility Layer → Preparation Backend → Validator → Importer`

Cada etapa reporta progresso estruturado e pode cancelar com segurança. A UI não deve executar trabalho pesado na thread principal.

## 1. Compatibility Layer

A camada deve ser independente da interface e evitar referências diretas ao Horizon na lógica central.

Responsabilidades:
- identificar versão do Android, ABI, arquitetura e capacidades disponíveis;
- verificar presença, arquitetura e hash de bibliotecas nativas antes de carregá-las;
- consultar uma matriz explícita de compatibilidade entre formato de entrada, versão do backend e destino;
- expor capacidades como `nativePreparation`, `horizonBackend`, `safeImport` e `progressReporting`;
- selecionar adaptador compatível ou retornar erro acionável; nunca fingir que houve fallback bem-sucedido;
- manter integração com Horizon atrás de uma interface, para permitir outro backend Android no futuro.

A camada não deve tentar contornar restrições do Android, carregar binários de arquitetura incompatível ou depender de caminhos privados fixos.

## 2. Interfaces conceituais

- `DeviceCapabilities`: SDK, ABI suportada, recursos de armazenamento e capacidades verificadas.
- `BackendDescriptor`: nome, versão, ABI, hash e capacidades declaradas.
- `CompatibilityReport`: compatível/incompatível, verificações executadas, avisos e motivo.
- `PreparationJob`: estado, etapa atual, progresso, cancelamento e resumo final.
- `ImportResult`: arquivos instalados, ignorados, falhas e validações.

Os tipos concretos devem ser definidos no código Android quando a estrutura Gradle for introduzida.

## 3. Segurança e robustez

- Usar Storage Access Framework (`ACTION_OPEN_DOCUMENT`/`ACTION_OPEN_DOCUMENT_TREE`) em vez de depender de `/storage/emulated/0` fixo.
- Validar caminhos de arquivo e impedir path traversal em arquivos compactados.
- Extrair para uma área temporária e só promover o resultado depois da validação.
- Evitar sobrescrita silenciosa; preservar backup/rollback quando necessário.
- Mostrar progresso por etapas e distinguir erro, aviso, cancelamento e conclusão.
- Registrar diagnósticos úteis sem incluir conteúdo privado dos arquivos.

## 4. Backends

`PreparationBackend` deve permitir ao menos um backend Android nativo e um adaptador Horizon opcional. A implementação Horizon só pode ser ativada quando sua API, artefatos e licenças estiverem verificadas. A camada de compatibilidade deve conseguir desativá-la sem quebrar a UI nem o fluxo de importação.

## 5. Testabilidade

Testes unitários para matriz de compatibilidade, validação de manifestos, progresso e caminhos de erro. Testes instrumentados para seleção de arquivos, cancelamento, armazenamento com SAF e importação. A integração real deve ser testada em ARM64 e em dispositivos Android representativos; emulador sozinho não valida drivers GPU.