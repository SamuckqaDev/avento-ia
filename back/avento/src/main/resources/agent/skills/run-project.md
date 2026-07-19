# Descobre e inicia o projeto usando os scripts que ele realmente oferece
Gatilhos: subir projeto, suba projeto, rodar projeto, rode projeto, iniciar projeto, inicie projeto

Procedimento:
1. Leia package.json, pom.xml ou os manifests equivalentes para descobrir módulos e scripts.
2. Verifique processos existentes com terminal_list para não duplicar serviços.
3. Inicie dependências curtas necessárias com terminal_run quando o projeto as declarar.
4. Use terminal_start para cada processo longo, no diretório correto e com o comando real do projeto.
5. Leia terminal_logs de cada processId e confirme URL/porta somente quando aparecer nos logs.

Não invente comandos nem abra um segundo processo se um equivalente já estiver ativo.
