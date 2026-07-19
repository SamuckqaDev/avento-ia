# Moderniza dependências do Avento em etapas compatíveis e verificadas
Gatilhos: atualizar dependencias avento, atualizar dependências avento, dependencia obsoleta, dependência obsoleta

1. Liste versão declarada, resolvida, runtime necessário e componente dono.
2. Consulte notas oficiais de release e migração das versões que alteram comportamento.
3. Atualize um grupo compatível por vez, como Spring Boot e Spring AI ou Vite e tooling React.
4. Remova overrides apenas depois de confirmar o grafo resolvido.
5. Compile e teste após cada grupo; valide startup para dependências nativas.
6. Confira arquitetura e bibliotecas dinâmicas de Whisper, Piper, FFmpeg, MLX e Netty no macOS.
7. Atualize lockfiles e documentação sem incluir caches, modelos, virtualenvs ou builds.

Não force tudo para `latest`; registre bloqueios reais das atualizações adiadas.
