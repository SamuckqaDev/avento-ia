# Cria um projeto NestJS na pasta indicada usando o CLI oficial
Gatilhos: criar nestjs, crie nestjs, cria nestjs, criando nestjs, novo nestjs, projeto nestjs, criar nest js, crie nest js, cria nest js

Use terminal_run para criar o projeto com o CLI oficial do NestJS:

npx --yes @nestjs/cli@latest new <nome-do-projeto> --package-manager npm --skip-git

Regras:
- O path da chamada é a pasta onde o projeto deve ficar (ex.: .../back). Se essa pasta não existir, crie antes com create_directory.
- Se o usuário deu um nome pro projeto (ex.: api-gateway, core-service, auth-service), use esse nome no lugar de <nome-do-projeto> — o CLI cria a subpasta sozinho. Se o usuário quer o scaffold direto dentro da pasta atual, use . como nome.
- Não pergunte qual gerenciador de pacotes usar nem se deve inicializar git — as flags acima já resolvem isso.
- Passe timeoutSeconds: 300 nessa chamada de terminal_run — o comando baixa o CLI e instala dependências, pode passar de 2 minutos.
- Nunca use create_vite_project pra isso — Vite não é NestJS.
- Depois que o comando terminar com sucesso, confirme em uma frase curta o que foi criado. Não liste próximos passos a menos que o usuário peça.
- Se o comando falhar, mostre o erro real. Não invente que funcionou.
