# Roadmap

## Fase 0 — Repositório e critérios
- [x] Criar repositório isolado.
- [x] Registrar direção Android-first e arquitetura da camada de compatibilidade.
- [ ] Importar a fonte real da versão 0.9.7 e verificar artefatos disponíveis.
- [ ] Registrar hash/ABI da biblioteca nativa recebida e confirmar licença/origem.

## Fase 1 — Aplicativo mínimo compilável
- [ ] Adicionar projeto Gradle/Android e workflow de build.
- [ ] Criar UI simples com seleção de entrada e destino via SAF.
- [ ] Implementar coordenador de tarefas com progresso, cancelamento e erros legíveis.
- [ ] Adicionar testes unitários e validação CI.

## Fase 2 — Preparação nativa
- [ ] Portar a etapa de preparação para código executado dentro do app, sem Termux.
- [ ] Validar manifestos, versões, arquivos e integridade antes de modificar o destino.
- [ ] Mostrar progresso real por etapa, sem porcentagens inventadas.
- [ ] Preservar a importação da pasta gerada para teste manual.

## Fase 3 — Camada de compatibilidade
- [ ] Implementar detecção de SDK, ABI e capacidades.
- [ ] Definir interfaces de backend e relatório de compatibilidade.
- [ ] Isolar o adaptador Horizon e suportar fallback seguro.
- [ ] Criar testes para combinações suportadas e incompatíveis.

## Fase 4 — Empacotamento e testes
- [ ] Integrar `libhorizon_android.so` somente após verificar arquivo, hash e ABI.
- [ ] Configurar GitHub Actions para compilar APK sem recompilar Rust quando o backend pré-compilado for válido.
- [ ] Publicar APK de teste como artifact, não como release estável automática.
- [ ] Testar instalação, importação e logs no Android real.

## Critérios para declarar pronto
- Build reproduzível em CI.
- Nenhuma dependência obrigatória do Termux.
- Importação testada com pasta real e erros recuperáveis.
- Relatório de compatibilidade verdadeiro e auditável.
- Biblioteca nativa verificada, ou backend Horizon desativado claramente.