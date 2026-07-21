# Verifica o projeto e corrige até passar
Gatilhos: verifica o projeto, roda os testes, valida o projeto, o build passa, ta compilando, corrige ate passar, verifica se quebrou
Ferramenta: verify_project

Depois de qualquer edição de código, feche o ciclo editar → verificar → corrigir:

1. Chame `verify_project` com o caminho absoluto do projeto (o workspace autorizado com package.json ou pom.xml).
2. Se `ok` for true, a mudança está válida — pode concluir.
3. Se `ok` for false, leia o campo `errorSummary`, abra os arquivos citados nos erros, corrija a causa e chame `verify_project` de novo.
4. Repita até `ok` ser true. Não anuncie que a tarefa está pronta enquanto a verificação não passar.
5. Se o mesmo erro persistir após algumas tentativas, pare e explique ao usuário o que travou, mostrando o `errorSummary` — não fique em loop.

Nunca invente que passou: só confie no resultado real de `verify_project`.
