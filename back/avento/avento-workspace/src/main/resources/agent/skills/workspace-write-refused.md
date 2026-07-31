# Diagnostica escrita recusada dentro de workspace autorizado
Gatilhos: path is outside the authorized workspace, fora do workspace autorizado, nao consigo escrever no projeto, não consigo escrever no projeto, write_file recusado, criar arquivo recusado

Ler funciona e escrever nao, no mesmo diretorio:

1. Confirme o sintoma: `read_file` passa e `write_file` ou `create_directory` falham com `SecurityException`.
2. Verifique link simbolico no caminho: `ls -ld` em cada nivel, ou compare `realpath` com o caminho informado. No macOS `/tmp` e `/var` sao links para `/private/...`.
3. Lembre a assimetria que causa isso: a raiz e registrada com `toRealPath()`, e um arquivo que ainda NAO existe so pode ser normalizado, nao resolvido.
4. Rode `WorkspaceSymlinkAuthorizationTest`; ele cobre os dois sentidos.
5. Cheque tambem o inverso, que e mais grave: link DENTRO do workspace apontando para fora deve ser recusado para arquivo novo, nao aceito.
6. Ao mexer em `WorkspaceAccessService`, resolva o ancestral existente mais proximo e reanexe o resto, para comparar onde a escrita realmente cai.

Nunca relaxe a verificacao para "passar": ela e a fronteira que impede o agente de escrever fora da raiz.
