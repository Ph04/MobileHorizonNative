# Roadmap

## Fase 0 — Fonte e critérios
- [x] Criar repositório isolado.
- [x] Registrar direção Android-first e arquitetura da camada de compatibilidade.
- [x] Receber e auditar a fonte 0.9.9, incluindo tamanho, SHA-256, ABI e símbolos do binário nativo.
- [x] Registrar os limites e a dependência de versão da fonte anterior.

## Fase 1 — Aplicativo mínimo compilável
- [x] Adicionar módulos Gradle/Android e workflow de build.
- [x] Criar UI Android nativa com seleção de pasta e ZIP via SAF.
- [x] Implementar importação em background com progresso, cancelamento cooperativo e erros legíveis.
- [x] Adicionar staging, substituição após validação básica e proteções contra caminhos ZIP inseguros.
- [x] Adicionar testes unitários iniciais para a camada de compatibilidade.
- [ ] Confirmar que a CI realmente compila o APK e corrigir possíveis falhas encontradas.

## Fase 2 — Preparação nativa
- [ ] Portar a etapa de preparação para código executado dentro do app, sem Termux.
- [ ] Validar integralidade e versão do conjunto de saída antes de ativá-lo.
- [x] Mostrar progresso de importação com valores reais; a porcentagem é omitida quando o total é desconhecido.
- [x] Preservar a importação manual da pasta gerada para testes.
- [ ] Verificar cancelamento e rollback sob condições reais de armazenamento/encerramento do processo.

## Fase 3 — Camada de compatibilidade
- [x] Implementar detecção de SDK, ABI, SAF e memória reportada.
- [x] Definir interfaces de backend e relatório codificado de compatibilidade.
- [x] Exigir hash SHA-256 com formato válido e hash declarado para o adaptador Horizon.
- [ ] Verificar o hash real do artefato antes de habilitar o carregamento nativo.
- [ ] Isolar o adaptador Horizon e suportar fallback seguro.
- [x] Adicionar testes para combinações compatíveis e incompatíveis.

## Fase 4 — Empacotamento e testes
- [x] Configurar GitHub Actions para testar o núcleo e tentar empacotar um APK de depuração.
- [ ] Confirmar sucesso do workflow no GitHub e instalar o APK em Android real.
- [ ] Integrar a biblioteca pré-compilada ao fluxo opcional sem recompilar Rust a cada build, depois de verificar origem/licença e hash.
- [ ] Testar importação de uma pasta real, arquivos ZIP grandes e relatório de erros no telefone.
- [ ] Testar inicialização nativa, Vulkan e gameplay em ARM64.

## Critérios para declarar pronto
- Build reproduzível em CI.
- Nenhuma dependência obrigatória do Termux para o fluxo final.
- Importação testada com pasta real e falhas recuperáveis.
- Relatório de compatibilidade verdadeiro e auditável.
- Biblioteca nativa verificada, ou backend Horizon desativado claramente.
- Preparador Android e renderer testados no aparelho, sem alegar funcionamento a partir apenas de verificações estáticas.
