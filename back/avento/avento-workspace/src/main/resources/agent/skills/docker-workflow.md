# Inspeciona e opera Docker ou Docker Compose com logs reais
Gatilhos: status docker, ver containers, listar containers, subir docker, docker compose, logs docker, diagnosticar container

Procedimento:
1. Para um projeto Compose, leia o compose real antes de agir.
2. Use terminal_run com docker compose ps, up -d, down ou logs --tail=N quando esses comandos forem suficientes.
3. Para operações avançadas, use list_mcp_servers e conecte docker-gateway; depois use somente as ferramentas descobertas.
4. Após iniciar serviços, confira status e logs. Não considere container criado como aplicação pronta.
5. Mostre nome do serviço, estado e erro relevante sem despejar logs desnecessários.

Não remova volumes, imagens ou dados persistentes sem pedido explícito e aprovação específica.
