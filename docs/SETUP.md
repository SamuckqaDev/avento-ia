# Avento Local Setup

Este guia deixa o Avento pronto para rodar como assistente local de projetos: backend Spring Boot, frontend React/Vite, Ollama, PostgreSQL, Redis Vector Store, login local com cookie JWT, execucao segura de validacoes e voz local opcional.

## Pre-requisitos

Obrigatorios:

- Java 21.
- Maven.
- Node.js, npm e npx.
- Docker Desktop, Docker Engine ou Colima.
- Ollama.

Recomendados:

- FFmpeg para conversao de audio.
- Whisper.cpp para transcricao local.
- Piper para voz local em portugues.
- ComfyUI local em `~/ComfyUI` para geracao visual com checkpoints. O script pode instalar o runtime inicial automaticamente.

Voce pode checar o ambiente com:

```sh
./scripts/check-local-deps.sh
```

## Fluxo Rapido

Para subir o ambiente local com Colima/Docker, Redis, backend e frontend:

```sh
./scripts/dev-up.sh
```

O script:

- Inicia o Colima se o Docker nao estiver respondendo e o Colima estiver instalado.
- Sobe PostgreSQL e Redis Stack com identidade Docker estavel pelo script `prepare-docker-stack.sh`.
- Migra automaticamente dados persistentes de containers criados pela antiga pasta do projeto, mantendo os volumes de origem como backup.
- Prepara o Docker MCP Gateway oficial em modo compativel com Colima antes de iniciar o backend.
- Configura o backend para usar o PostgreSQL local do Docker/Colima.
- Reutiliza o Ollama nativo quando ele ja esta respondendo ou inicia `ollama serve` automaticamente, com logs em `tmp/dev/ollama.log`.
- Detecta o ComfyUI em `~/ComfyUI`, inicia a API em `127.0.0.1:8188` quando encontrado e registra logs em `tmp/dev/comfyui.log`.
- Confere os modelos WAN 2.1 de video e, na primeira subida, baixa aproximadamente 9,8 GB com retomada automatica.
- Instala dependencias do frontend se `node_modules` ainda nao existir.
- Se a porta `8000` estiver ocupada por um backend antigo, escolhe uma porta livre automaticamente.
- Sobe backend e frontend, salva os logs em `tmp/dev/` e transmite ao vivo no mesmo terminal os logs da aplicacao, Ollama e Docker. Rodadas do agente registram inicio, conclusao ou falha; imagens do ComfyUI registram fila, progresso a cada 10 segundos e arquivo final.
- Roda o smoke test automaticamente e mostra a URL correta para abrir.
- Exporta a mesma credencial root para o backend e o smoke test e a exibe no terminal antes e depois da inicializacao, evitando divergencia com configuracoes locais antigas.

Para incluir uma validacao leve do command runner no smoke test automatico:

```sh
AVENTO_SMOKE_RUN_VALIDATION=1 ./scripts/dev-up.sh
```

Se quiser forcar portas especificas:

```sh
AVENTO_BACKEND_URL=http://127.0.0.1:18000 \
AVENTO_FRONTEND_URL=http://127.0.0.1:5174 \
./scripts/dev-up.sh
```

Para uma instalacao do ComfyUI em outro diretorio, use `AVENTO_COMFYUI_DIR=/caminho/ComfyUI`. Para nao inicia-lo automaticamente, use `AVENTO_COMFYUI_AUTOSTART=0`. A primeira subida instala o runtime do ComfyUI em `~/ComfyUI` por padrao e registra o processo em `tmp/dev/comfyui-setup.log`. O pipeline Realistic Vision e preparado por `./scripts/setup-comfyui-image.sh`; use `AVENTO_COMFYUI_IMAGE_AUTO_INSTALL=0` para impedir o download automatico. O FLUX.2 Klein 4B e preparado por `./scripts/setup-comfyui-flux2.sh`; no Apple Silicon, o script escolhe automaticamente o checkpoint BF16 compativel com Metal (aproximadamente 15,6 GB com encoder e VAE), enquanto outras plataformas usam FP8 (aproximadamente 11,6 GB). Use `AVENTO_COMFYUI_FLUX2_AUTO_INSTALL=0` para desabilitar esses downloads automaticos. Os modelos de video WAN 2.2 sao baixados automaticamente; use `AVENTO_COMFYUI_VIDEO_AUTO_INSTALL=0` para desabilitar ou execute `./scripts/setup-comfyui-video.sh` para instalar e retomar manualmente.

O Ollama usa `http://127.0.0.1:11434` por padrão. `dev-up.sh` inicia `ollama serve` quando necessário e encerra essa instância ao receber `Ctrl+C`; uma instância que já estava rodando é preservada. Use `AVENTO_OLLAMA_AUTOSTART=0` para desativar essa subida ou `AVENTO_OLLAMA_URL` para alterar a URL entregue ao backend. Os pacotes MCP executados por `npx` usam o cache isolado `.avento-tools/npm-cache`, evitando que um cache global corrompido impeça a conexão; o caminho pode ser alterado com `AVENTO_NPM_CACHE_DIR`.

## PostgreSQL e Redis

O ambiente Docker/Colima sobe dois servicos:

- PostgreSQL para usuarios, sessoes, historico de tokens/acessos, chats e dados relacionais.
- Redis Stack para fila do agente, eventos em tempo real, contexto recente, RAG e caches locais.

O RAG mantém um manifesto por projeto em Redis, usa hash dos arquivos para indexar somente alterações, armazena metadados de namespace/caminho/hash/chunk e aplica cache de busca por projeto. Ao desconectar uma pasta, o frontend chama `POST /api/rag/clear` para remover seus chunks e o manifesto. A busca nunca mistura documentos de outra raiz conectada.

O chat usa Redis Streams sem transformar Redis em banco principal. Jobs e Outbox ficam no
PostgreSQL, referencias entram em `avento:jobs:agent`, eventos entram em
`avento:events:{runId}` e o cache recente usa `avento:context:{userId}:{chatId}`. Veja o passo a passo em
[Execucao assincrona com Redis](REDIS_EXECUTION.md).

Suba com:

```sh
./scripts/prepare-docker-stack.sh
```

No macOS com Colima, suba o runtime antes:

```sh
colima start
./scripts/prepare-docker-stack.sh
```

Confirme que os containers estao de pe:

```sh
docker compose ps
```

O projeto Compose sempre usa o nome `avento-ia`, independentemente do nome da pasta do clone. Os volumes persistentes tambem possuem nomes estaveis: `avento-postgres-data` e `avento-redis-data`. Na primeira subida apos uma migracao da estrutura antiga, o script para somente os containers legados, copia os dados para esses volumes, recria os containers e preserva os volumes antigos como backup.

O Avento usa `docker mcp gateway run --servers docker` para expor o Docker ao agente. Em Colima ou Docker Engine sem Docker Desktop, define `DOCKER_MCP_IN_CONTAINER=1`, conforme suportado pelo gateway oficial. A primeira execucao pode baixar a imagem `docker:cli`; o `dev-up.sh` faz isso antes do backend para evitar timeout no handshake MCP. Para desativar somente essa integracao, use `AVENTO_MCP_DOCKER_ENABLED=false`.

## Ollama

No macOS, rode o Ollama nativo no host, fora do Docker/Colima. Assim ele pode usar aceleracao Metal nos chips Apple Silicon. Em container no macOS, o Ollama fica sem passthrough de GPU/Metal e tende a cair para CPU, o que e bem mais lento.

Instale os modelos recomendados:

```sh
ollama pull qwen3:8b
ollama pull qwen2.5vl:7b
ollama pull llama3.2
ollama pull nomic-embed-text
```

Os modelos de geracao de imagens nao rodam pelo Ollama. RealVisXL, Realistic Vision e FLUX.2 Klein sao instalados no ComfyUI pelos scripts descritos na secao de geracao de imagens. O modelo visual do Ollama e usado somente para leitura e para a revisao opcional do resultado.

Use `qwen3:8b` como modelo padrao do Avento quando o agente tiver ferramentas locais habilitadas. Nos testes locais, ele respondeu saudacoes em portugues sem acionar ferramentas e chamou `open_app` quando o pedido foi explicito. O `llama3.2` fica como fallback mais leve, mas pode confundir saudacoes curtas com tool calls quando muitas ferramentas estao expostas. O seletor da interface marca modelos pesados e prefere o modelo recomendado quando disponivel.

Para leitura de imagens, anexe arquivos PNG, JPEG ou WebP no chat. O frontend identifica os modelos com visao e troca automaticamente para `avento.agent.vision-model` (`AVENTO_AGENT_VISION_MODEL`, padrao `qwen2.5vl:7b`) quando o modelo selecionado for apenas textual. O backend repete essa validacao para clientes de API e mantem as ferramentas desativadas enquanto houver imagem no contexto, permitindo que modelos visuais sem tool calling analisem o anexo normalmente.

Para geracao de imagens, o agente usa a ferramenta aprovada `generate_image`, cria um job em `image_generation_jobs` e devolve seu identificador imediatamente. Um worker local chama o provider visual selecionado e salva PNGs em `~/Pictures/Avento Generated Images`. Essa rota nao depende de workspace ou MCP. O modelo padrao e configurado por `avento.image.default-model` ou `AVENTO_IMAGE_DEFAULT_MODEL`. Antes de montar o prompt para o SDXL, o backend traduz o pedido para ingles com o modelo de conversa (CLIP so entende ingles); controle com `AVENTO_IMAGE_TRANSLATION_ENABLED` (padrao `true`), `AVENTO_IMAGE_TRANSLATION_MODEL` (padrao: o modelo do agente) e `AVENTO_IMAGE_TRANSLATION_TIMEOUT_SECONDS` (padrao `45`). Em caso de falha na traducao, o prompt original e usado sem interromper a geracao.

Cada checkpoint usa automaticamente um preset de geracao (sampler, scheduler, passos, CFG e resolucao nativa por nivel de qualidade), definido em `comfyui/model-presets.json` — SDXL, FLUX.2 Klein e um padrao para SD 1.5. Para ajustar ou adicionar presets sem recompilar, crie `~/.avento/image-presets.json` (ou aponte `AVENTO_IMAGE_PRESETS_FILE` para outro caminho) com o mesmo formato; entradas locais tem prioridade e o arquivo e relido a cada geracao. Exemplo:

```json
{
  "presets": [
    {
      "name": "meu-realvis",
      "match": ["realvisxl"],
      "sampler": "dpmpp_2m",
      "scheduler": "karras",
      "steps": { "draft": 20, "balanced": 28, "quality": 36 },
      "refinementSteps": { "draft": 5, "balanced": 8, "quality": 12 },
      "cfg": { "draft": 5.5, "balanced": 5.0, "quality": 4.5 },
      "longEdge": { "draft": 768, "balanced": 1024, "quality": 1152 }
    }
  ]
}
```

Ajustes manuais na interface continuam vencendo o preset: o seletor draft/balanced/quality escolhe a faixa dentro do preset, um CFG definido na mao sobrepoe o valor do preset e o aspect ratio molda a resolucao.

O campo opcional `promptStyle` controla o que chega ao encoder do modelo: `tags` (padrao, SDXL/SD 1.5) envia o prompt reforcado do planner; `natural` (FLUX.2 Klein) envia o pedido traduzido como frase natural, que e o que o encoder LLM do FLUX.2 entende. O FLUX.2 Klein destilado usa 4 passos e CFG 1.0 por recomendacao oficial — aumentar passos nele piora o resultado; a variante base (`flux-2-klein-base-*.safetensors`) usa o preset proprio de 16-24 passos com CFG 3.5-4.5. Quando a mensagem possui imagens, a ultima e enviada como referencia e o campo `Uso da referencia` decide se ela orienta composicao, identidade ou uma transformacao img2img.

Para exportar documentos, a ferramenta `generate_pdf` converte Markdown (com tabelas) ou HTML em PDF via `openhtmltopdf` e `commonmark-java` (dependencias Maven ja declaradas no `pom.xml`). O PDF e salvo na mesma pasta de midia, vinculado a conversa e servido por `GET /api/media/{filename}`; nao exige configuracao adicional. A skill `/research` faz pesquisa na internet reusando os servidores MCP de web (`fetch`, Playwright) e sintetiza o resultado; ela declara `MaxRodadas:` para ter mais rodadas que o teto global de 6, controlado por `AVENTO_AGENT_MAX_TOOL_ROUNDS`.

Uma geracao de imagem ou video concluida e um resultado terminal do agente: o backend exibe a midia e encerra a rodada sem pedir ao modelo conversacional que reinterprete o resultado. Isso evita recusas ou explicacoes contraditorias depois que o ComfyUI ja produziu e salvou o arquivo.

As imagens e os videos gerados aparecem minimizados por padrao no chat. A escolha de expandir ou recolher cada midia e preservada durante a sessao, mesmo quando o streaming atualiza e renderiza novamente o balao. Despachos assincronos do stream reutilizam o contexto autenticado da requisicao inicial para impedir `AccessDenied` depois que a resposta ja comecou.

Cada stream fica vinculado ao `chatId` que iniciou a solicitacao. Trocar de conversa nao aborta a execucao original e nao move Thinking, chunks, eventos, midia ou voz para o chat aberto. O rascunho em andamento e restaurado ao voltar para a conversa de origem, a resposta final e persistida nela e o botao de parar cancela apenas os streams do chat atual. Chats diferentes podem manter execucoes simultaneas; excluir um chat cancela primeiro os streams vinculados a ele.

O Avento usa o ComfyUI por padrao para gerar imagens. O RealVisXL V5 e o checkpoint recomendado e usa `sdxl-text-to-image-api.json`, resolucao nativa entre 768 e 1024 px, VAE SDXL, segundo passe, detailers e controles SDXL. O Realistic Vision V6 continua disponivel no workflow SD 1.5 legado, e o FLUX.2 Klein 4B usa o workflow destilado de quatro passos. Referencias e detailers nao sao aplicados ao FLUX.2. Se houver menos de 4 GB livres, o backend reduz detailers; abaixo de 2 GB, reduz qualidade e segundo passe para evitar encerramento do processo. O guardiao pode ser desativado com `AVENTO_COMFYUI_MEMORY_GUARD_ENABLED=false`. O timeout padrao e de 20 minutos e pode ser alterado por `AVENTO_COMFYUI_IMAGE_TIMEOUT_MINUTES`.

O FLUX.2 Klein e mantido como opcao de uso geral. Para fotografia, estrutura, identidade, pose, segundo passe e detailers, use o RealVisXL V5, que e o modelo padrao recomendado.

Os controles de imagem funcionam assim:

- `O que gerar?`: `Automatico` infere o assunto pelo prompt. As opcoes `Pessoa`, `Objeto / produto`, `Ambiente / cenario`, `Veiculo` e `Animal` fixam a categoria principal, impedem detailers incompativeis e adicionam condicionamento proprio para o tipo escolhido.
- `CFG`: aderencia ao prompt. Use `5.0` a `6.5` para fotografia; valores altos tendem a endurecer pele, contraste e anatomia.
- `Uso da referencia`: `Composicao` usa Depth ou Canny; `Identidade` usa IP-Adapter; `Transformacao` usa img2img.
- `Estrutura`: Depth preserva volumes e perspectiva; Canny preserva contornos. `Forca da estrutura` entre `0.6` e `0.85` costuma ser um bom ponto inicial.
- `Fidelidade a imagem anexada`: controla a forca de identidade ou img2img. `0.55` permite mudancas maiores; `0.65` equilibra preservacao e liberdade; `0.8` preserva fortemente a referencia.
- `Segundo passe`: melhora resolucao e detalhes. A forca entre `0.25` e `0.35` preserva melhor a composicao; acima de `0.45` permite mudancas maiores.
- `Detalhamento`: `Rosto` e o padrao mais rapido. `Rosto e maos` custa mais tempo, mas reduz olhos, dedos e maos deformados.
- `Pose de referencia`: envia apenas a estrutura corporal da imagem escolhida. `0.65` a `0.85` costuma equilibrar fidelidade e liberdade.
- `Validar resultado`: usa o modelo visual local para conferir assunto, quantidade, atributos, objetos, cores, pose, relacoes espaciais e realismo. `Tentativas extras` define de zero a duas novas geracoes quando a pontuacao ficar abaixo de `AVENTO_COMFYUI_ADHERENCE_MIN_SCORE` (padrao `85`). A revisao aumenta o tempo e preserva o primeiro resultado se o modelo visual estiver indisponivel.
- `Seed fixa`: repete a mesma base para comparar ajustes. Desligue para explorar composicoes novas.

O instalador verificado baixa aproximadamente 4 GB e pode ser executado ou retomado manualmente:

```sh
./scripts/setup-comfyui-image.sh
./scripts/setup-comfyui-image.sh --check
./scripts/setup-comfyui-image.sh --verify
```

O pipeline SDXL recomendado e instalado e verificado separadamente:

```sh
./scripts/setup-comfyui-sdxl.sh
./scripts/setup-comfyui-sdxl.sh --check
./scripts/setup-comfyui-sdxl.sh --verify
```

O FLUX.2 pode ser instalado e verificado separadamente:

```sh
./scripts/setup-comfyui-flux2.sh
./scripts/setup-comfyui-flux2.sh --check
./scripts/setup-comfyui-flux2.sh --verify
```

`--check` faz a verificacao rapida usada na subida. `--verify` recalcula os hashes dos arquivos grandes e serve para diagnosticar download corrompido.

Custom nodes novos exigem reinicio do ComfyUI. O `dev-up.sh` consulta `/object_info` e reinicia automaticamente a instancia local quando DWPose, Impact Pack, IP-Adapter ou os preprocessadores SDXL instalados ainda nao aparecem na API.

Os nomes SDXL podem ser substituidos por `AVENTO_COMFYUI_DEFAULT_MODEL`, `AVENTO_COMFYUI_SDXL_VAE`, `AVENTO_COMFYUI_SDXL_OPENPOSE_MODEL`, `AVENTO_COMFYUI_SDXL_CANNY_MODEL` e `AVENTO_COMFYUI_SDXL_DEPTH_MODEL`, desde que o workflow seja compativel. O workflow pode ser substituido por `AVENTO_COMFYUI_SDXL_WORKFLOW`.

Para videos, `generate_video` usa o workflow `text-to-video-api.json` e o WAN 2.2 TI2V 5B. O modo `auto` anima a imagem gerada mais recente do chat quando ela existe; `image` exige essa referência e `text` gera do zero. O script `setup-comfyui-video.sh` instala o diffusion model, o encoder UMT5 FP8 e o VAE nas pastas corretas, removendo os modelos WAN 2.1 antigos depois que o novo conjunto estiver completo. Use `AVENTO_COMFYUI_VIDEO_CLEANUP_LEGACY=0` para preservar os arquivos antigos. Os nomes podem ser substituidos por `AVENTO_COMFYUI_VIDEO_DIFFUSION_MODEL`, `AVENTO_COMFYUI_VIDEO_TEXT_ENCODER` e `AVENTO_COMFYUI_VIDEO_VAE` quando um workflow compativel for configurado.

O botão de clipe do chat envia PDF, Office, EPUB, ZIP, texto e código para `POST /api/documents/extract`. O backend lê texto simples diretamente e usa o MarkItDown local nos formatos binários, mantendo apenas uma cópia temporária durante a extração. A instalação padrão fica em `.avento-tools/mcp`, sempre resolvida a partir da raiz do projeto; `AVENTO_MARKITDOWN_COMMAND` pode apontar para outro executável. Como ambientes virtuais Python guardam caminhos absolutos, `scripts/setup-local-mcps.sh` valida os comandos e reconstrói automaticamente a instalação quando o repositório é movido ou renomeado. O limite HTTP é 50 MB por arquivo e o trecho entregue ao modelo pode ser ajustado com `AVENTO_DOCUMENT_ATTACHMENT_MAX_CONTEXT_CHARS` (padrão: 5.000 caracteres).

O detalhamento de pessoas usa detecção por região, ordenação por confiança e limite coerente com a quantidade de sujeitos: até um rosto e duas mãos por pessoa. Regiões excedentes são descartadas antes do inpainting, evitando que falsos positivos do detector desenhem rostos sobre torso, abdômen ou outras partes do corpo. O threshold facial é mais estrito e o denoise do detailer é limitado para preservar a anatomia produzida pelo passe principal.

Imagem e video sao assincronos. O backend cria registros em `image_generation_jobs` e `video_generation_jobs`, devolve o identificador ao chat e usa um worker unico por tipo para respeitar a memoria da maquina local. A interface consulta `GET /api/images/{jobId}` ou `GET /api/videos/{jobId}` com Axios a cada dois segundos e mostra etapa, progresso estimado e tempo restante. `POST /api/images/{jobId}/cancel` e `POST /api/videos/{jobId}/cancel` cancelam o job; no ComfyUI, o prompt ativo identificado pelo worker tambem e removido ou interrompido. Jobs nao finalizados sao recuperados quando o backend reinicia. O progresso e a previsao sao estimativas calculadas pelas opcoes de qualidade, refinamento, validacao, dimensoes, frames, passos e tempo decorrido.

O backend compacta historico antigo, remove prompts de sistema duplicados do frontend e envia apenas as ferramentas relevantes para o pedido antes de chamar o modelo. Continuações curtas preservam o ultimo pedido explicito em `[Conversation Continuity]`. Alem de `avento.agent.num-ctx`, podem ser ajustados `temperature`, `top-p`, `top-k`, `repeat-penalty` e os tres limites de compactacao. O RAG aplica `avento.rag.similarity-threshold` antes de incluir trechos no contexto, reduzindo resultados semanticamente fracos.

### Politica e midias geradas

Ao apagar uma conversa, o Avento remove permanentemente as mensagens, jobs de imagem e video e as imagens ou vídeos gerados naquele chat. Jobs ativos sao cancelados antes da exclusao. Se um artefato estiver bloqueado pelo sistema, a exclusão é interrompida e o modal mostra o erro para evitar que o banco seja apagado enquanto o arquivo permanece órfão. Pastas e documentos externos apenas conectados como contexto são desvinculados, nunca apagados do projeto do usuário.

As instrucoes publicas de politica ficam fora do codigo Java em `back/avento/src/main/resources/agent/policies/`: `maximum.md`, `professional.md` e `protected.md`. O modo padrao e `maximum`, configurado em `avento.agent.policy-mode` ou pela variavel `AVENTO_AGENT_POLICY_MODE`. A politica controla o contexto e a forma de resposta do Avento; limites proprios do modelo local continuam valendo.

Os arquivos de politica e os procedimentos internos de imagem e traducao sao escritos em ingles para reduzir ambiguidades de instruction-following nos modelos locais. Isso nao altera o idioma da interface nem das respostas: `agent/instructions/identity.md` continua exigindo que o Avento preserve o idioma do usuario.

No modo `maximum`, transformacoes de texto fornecido pelo usuario (traducao, transcricao, resumo, revisao, classificacao e analise) sao separadas da criacao de conteudo novo. Uma traducao deve preservar significado, tom, palavroes e linguagem adulta ou explicita, sem censura editorial. A skill `translate-content`, em `agent/skills/translate-content.md`, ativa automaticamente para pedidos diretos de traducao e reforca esse contrato sem remover o texto original da conversa.

Configuracoes pessoais nao precisam ser publicadas. O backend procura primeiro `{modo}.md` em `~/.avento/policies/` e usa a politica embutida apenas quando o override local nao existe. O diretorio pode ser alterado com `AVENTO_AGENT_POLICY_OVERRIDE_DIR`. Esses arquivos ficam fora do repositorio e nao devem ser adicionados ao Git. Depois de criar ou alterar um override, reinicie o backend para que as proximas conversas usem o novo conteudo.

Imagens e videos gerados pelo Avento ficam em `~/Pictures/Avento Generated Images`, aparecem automaticamente no balao da resposta e ficam disponiveis na secao `Midias` da barra lateral. Cada arquivo e registrado no PostgreSQL com o chat e o usuario proprietarios; ao excluir o chat, o backend remove as mensagens, os registros de midia e os arquivos fisicos. O seletor de modelo de imagem no header consulta `GET /api/ai/models/images`; a escolha e enviada como `imageModel` e aplicada somente a chamadas `generate_image`. A API autenticada `GET /api/media?chatId={id}` lista apenas as midias do chat selecionado e `GET /api/media/{filename}` entrega o arquivo para exibicao. Referencias antigas salvas como caminho local ou `/api/media/...` sao vinculadas automaticamente quando a conversa e aberta.

Confirme que o Ollama esta respondendo em `http://localhost:11434`.

Se algum servico estiver dentro do Colima e precisar chamar o Ollama nativo do Mac, use o endereco de host da VM, geralmente `host.docker.internal` ou `host.lima.internal`, conforme sua versao/configuracao do Colima.

## Configuracao Local

O backend roda preso em loopback por padrao, em `127.0.0.1:8000`, para reduzir exposicao acidental da maquina.

Para usar ajustes especificos da sua maquina, copie o exemplo:

```sh
cp back/avento/src/main/resources/application-local.example.yml back/avento/src/main/resources/application-local.yml
```

Depois rode com o profile local:

```sh
mvn -f back/avento/pom.xml spring-boot:run -Dspring-boot.run.profiles=local
```

Campos mais comuns para ajustar:

```yaml
avento:
  agent:
    num-ctx: ${AVENTO_AGENT_NUM_CTX:8192}
    temperature: ${AVENTO_AGENT_TEMPERATURE:0.15}
    top-p: ${AVENTO_AGENT_TOP_P:0.9}
    top-k: ${AVENTO_AGENT_TOP_K:30}
    repeat-penalty: ${AVENTO_AGENT_REPEAT_PENALTY:1.08}
    enable-thinking: ${AVENTO_AGENT_ENABLE_THINKING:true}
    keep-alive: ${AVENTO_AGENT_KEEP_ALIVE:30m}
    max-tools-per-request: ${AVENTO_AGENT_MAX_TOOLS_PER_REQUEST:12}
    project-toolkit: ${AVENTO_AGENT_PROJECT_TOOLKIT:directory_tree,read_file,read_document,write_file,edit_file,delete_file,delete_directory,create_directory,search_files,terminal_run,terminal_start,terminal_logs}
    max-model-messages: ${AVENTO_AGENT_MAX_MODEL_MESSAGES:10}
    max-message-content-chars: ${AVENTO_AGENT_MAX_MESSAGE_CONTENT_CHARS:6000}
    max-total-message-content-chars: ${AVENTO_AGENT_MAX_TOTAL_MESSAGE_CONTENT_CHARS:8000}
  rag:
    similarity-threshold: ${AVENTO_RAG_SIMILARITY_THRESHOLD:0.62}
    candidate-limit: ${AVENTO_RAG_CANDIDATE_LIMIT:30}
    result-limit: ${AVENTO_RAG_RESULT_LIMIT:5}
  voice:
    ffmpeg-path: /opt/homebrew/bin/ffmpeg
    whisper-binary: ./back/whisper.cpp/build/bin/whisper-cli
    whisper-model: ./back/whisper.cpp/models/ggml-small.bin
    whisper-language: auto
    preferred-language: pt
    allowed-languages: pt,en,es
    whisper-prompt: "Avento. Portugues brasileiro natural. Transcreva exatamente o que foi dito. Nao acrescente nomes de aplicativos como Finder, Terminal, Brave ou VS Code a menos que tenham sido claramente falados."
    whisper-carry-initial-prompt: true
    whisper-beam-size: 5
    whisper-best-of: 5
    whisper-vad-enabled: true
    whisper-vad-model: ./back/whisper.cpp/models/ggml-silero-v6.2.0.bin
    whisper-vad-threshold: 0.50
    whisper-vad-min-silence-ms: 550
    whisper-vad-speech-pad-ms: 120
    piper-binary: ./piper_tts/.venv/bin/piper
    piper-model: ./piper_tts/pt_BR-faber-medium.onnx
    piper-model-pt: ./piper_tts/pt_BR-dii-high.onnx
    piper-model-en: ./piper_tts/en_US-lessac-medium.onnx
    piper-model-es: ""
    piper-length-scale: 0.95
    piper-noise-scale: 0.60
    piper-noise-width-scale: 0.80
    piper-sentence-silence: 0.18
    tts-cache-enabled: true
    tts-cache-ttl: PT6H
```

O frontend encerra uma fala apos cerca de 850 ms de silencio e envia o audio ao Whisper com VAD
local. Na resposta, o backend remove Markdown, codigo, URLs, metricas e emojis antes do Piper; textos
longos sao sintetizados em trechos menores e a reproducao comeca assim que o primeiro trecho fica
pronto. O idioma detectado na conversa escolhe a voz portuguesa ou inglesa configurada.

Configuracoes de banco e auth usadas pelo profile local:

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6379
      timeout: 2s
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/avento
    username: avento
    password: avento_dev_password

avento:
  execution:
    redis:
      enabled: true
      event-stream: avento:events
      event-ttl: 24h
      agent-job-stream: avento:jobs:agent
      dead-letter-stream: avento:dead-letter
      agent-consumer-group: avento-agent-workers
      context-ttl: 24h
      context-message-limit: 20
  auth:
    enabled: true
    bootstrap-enabled: true
    cookie:
      http-only: true
      secure: false
      same-site: Lax
    root:
      enabled: true
      email: ${AVENTO_AUTH_ROOT_EMAIL:admin@avento.local}
      password: ${AVENTO_AUTH_ROOT_PASSWORD}
      display-name: Avento Root
```

Em producao ou rede compartilhada, troque `AVENTO_AUTH_JWT_SECRET`, use HTTPS e marque `AVENTO_AUTH_COOKIE_SECURE=true`.

## Login Local

Nao existe senha padrao embutida: a aplicacao recusa subir se `AVENTO_AUTH_ROOT_PASSWORD` estiver vazia ou com menos de 8 caracteres. Esse tipo de segredo fica em `.env` na raiz do repo (fora do git, veja `.env.example`), nunca no `application.yml` — o backend le o `.env` sozinho ao iniciar, seja via `mvn`, IDE ou jar. `./scripts/dev-up.sh` gera uma senha aleatoria na primeira execucao e acrescenta em `.env` se ainda nao existir uma. Rodando o backend manualmente:

```sh
cp .env.example .env
# edite .env e defina AVENTO_AUTH_ROOT_PASSWORD
```

```text
Role: ROOT
```

Esse seed fica em `avento.auth.root.*` e pode ser desligado com `AVENTO_AUTH_ROOT_ENABLED=false`. Depois do login:

- O access JWT vai somente em cookie `HttpOnly`.
- O refresh nao e enviado ao navegador; ele fica associado a sessao no banco.
- O backend renova a sessao usando `sid/jti` do access token e rotaciona o `jti`.
- Reuso de token antigo apos refresh gera `401`.
- `GET /api/auth/access-history` e `GET /api/auth/token-history` expõem os ultimos eventos da conta logada.

O frontend usa um client Axios compartilhado com `withCredentials: true` em todas as chamadas REST `/api`; a unica credencial de validacao no navegador e o cookie JWT `HttpOnly` emitido pelo backend. Quando recebe `401`, ele tenta `/api/auth/refresh` uma vez usando esse mesmo cookie. Tentativas simultaneas de refresh sao coordenadas em uma unica chamada para evitar corrida de rotacao de `jti`. A criacao do job usa Axios em `POST /api/ai/runs`; apenas a leitura SSE usa `fetch` para consumir o `ReadableStream`, tambem com cookie e refresh coordenado.
Se uma chamada continuar retornando `401`, a interface mostra um aviso de sessao vencida com botao para revalidar ou voltar ao login. Respostas `403` aparecem como snackbar com status, rota e mensagem retornada pelo servidor, para evitar falhas silenciosas.

Toda resposta REST JSON chega pela rede como `{ "status": 200, "code": "SUCCESS", "data": ... }`.
O interceptor Axios entrega somente o valor de `data` aos hooks existentes. Em erro, `data.message`
traz a mensagem, `data.errors` lista erros de campos e `data.traceId` corresponde ao header
`X-Trace-Id`. Listas vazias sao `[]`. Use o envelope completo ao integrar outro cliente; nao tente
aplica-lo a SSE, WebSocket, `POST /api/voice/tts` ou `GET /api/media/{filename}`.

O historico de chats fica ligado ao usuario autenticado pelo cookie: `GET /api/chats`, `PATCH /api/chats/{id}` e as rotas de mensagens so retornam ou alteram conversas cujo `user_id` bate com o `AuthPrincipal` atual. `DELETE /api/chats/{id}` apaga definitivamente a conversa, suas mensagens e as imagens geradas referenciadas por ela, sempre depois da confirmacao no frontend. Em auth desabilitado, o controller preserva o comportamento permissivo para desenvolvimento isolado.

## Backend

Instale/compile e rode:

```sh
mvn -f back/avento/pom.xml spring-boot:run
```

Endpoints principais:

- `GET /api/health` para saude simples.
- `POST /api/auth/bootstrap` para criar o primeiro admin local.
- `POST /api/auth/login` para abrir sessao.
- `POST /api/auth/refresh` para renovar access cookie sem expor refresh ao frontend.
- `POST /api/auth/logout` para revogar sessao.
- `GET /api/auth/me` para usuario atual.
- `POST /api/projects/analyze` para analise de projeto.
- `POST /api/projects/run` para validacoes permitidas.
- `POST /api/filesystem/authorize` para liberar uma pasta local.
- `POST /api/ai/runs` para criar um job assincrono do agente.
- `GET /api/ai/runs/{runId}/events` para acompanhar texto, ferramentas e estado por SSE.
- `POST /api/ai/runs/{runId}/cancel` para cancelar uma execucao propria.

## Frontend

```sh
cd front
npm install
npm run dev
```

Abra a URL do Vite, normalmente `http://localhost:5173`.

## Validacao

Backend:

```sh
mvn -f back/avento/pom.xml test
```

Frontend:

```sh
cd front
npm run validate
```

O comando `validate` executa typecheck, lint e build.

## Voz Local

A voz e opcional e roda localmente. Sem Whisper/Piper configurados, as rotas de voz podem retornar erro de configuracao, mas o chat, analise de projetos, RAG, comandos e diffs continuam funcionando.

A transcricao usa Whisper.cpp local/offline. O profile local usa `avento.voice.whisper-language=auto`, mas só aceita idiomas em `avento.voice.allowed-languages` (`pt,en,es` por padrao). Se o Whisper detectar outro idioma, como russo, ou devolver alfabeto inesperado, o backend refaz em `preferred-language=pt` e nao envia lixo para o chat. O prompt local com vocabulario do Avento (`VS Code`, `Brave`, `Finder`, `Terminal`, etc.) e `--carry-initial-prompt` ajudam frases curtas de comando.

Ao iniciar o `whisper-cli`, o backend adiciona automaticamente a pasta atual do executavel ao caminho de bibliotecas dinamicas. Assim, builds locais do Whisper continuam funcionando depois que o repositorio e movido ou renomeado, mesmo que o binario tenha sido compilado com um `rpath` absoluto antigo.

Para comando de voz em portugues com nomes de apps, prefira `ggml-small.bin` ou maior. O `ggml-base.bin` funciona, mas costuma errar mais frases curtas como "fecha VS Code".

Arquivos esperados pelo setup sugerido:

- `back/whisper.cpp/build/bin/whisper-cli`
- `back/whisper.cpp/models/ggml-small.bin` ou outro modelo Whisper local
- `back/whisper.cpp/models/ggml-silero-v6.2.0.bin` para VAD local
- `piper_tts/.venv/bin/piper`
- `piper_tts/pt_BR-dii-high.onnx`
- `piper_tts/en_US-lessac-medium.onnx`

Esses artefatos sao grandes e devem ficar fora do git.

O TTS escolhe o modelo Piper pelo idioma detectado no texto da resposta. Configure `avento.voice.piper-model-en` com um modelo Piper em ingles para evitar o efeito de uma voz brasileira lendo texto em ingles; `piper-model-pt` fica para portugues e `piper-model-es` para espanhol. Se uma voz especifica nao estiver configurada, o Avento usa `piper-model` como fallback para manter o audio funcionando.

Os caminhos relativos de Piper e Whisper sao resolvidos pela raiz do projeto, mesmo quando Maven executa o processo dentro de `back/avento`. O `dev-up.sh` exporta `AVENTO_PROJECT_ROOT` automaticamente; em uma inicializacao manual fora da estrutura padrao, configure essa variavel com o caminho absoluto da raiz do Avento.

Se a pasta do Avento for movida ou renomeada, o lancador Python do virtualenv do Piper pode manter o caminho antigo no shebang. O backend detecta esse caso e executa o lancador pelo Python do proprio virtualenv, sem exigir reinstalacao do Piper.

No modo de voz em tempo quase real, se o usuario falar enquanto o Avento esta falando, o frontend corta o audio atual imediatamente, cancela a geracao em andamento e captura a nova fala antes de enviar outra rodada ao agente com o contexto recente. A captura WebSocket aguarda cerca de 850 ms de silencio antes de fechar a frase, envia o WebM inteiro e so entao manda `flush`, evitando transcricao de audio incompleto. O backend aumenta o buffer binario do WebSocket para aceitar frases de voz maiores sem fechar a conexao com erro `1009`. O TTS usa cache opcional no Redis (`avento:voice:tts:*`) para reaproveitar WAVs de frases iguais e reduzir latencia; se o Redis estiver indisponivel, o Piper continua sendo usado normalmente.

O botao de mute controla toda a reproducao do Avento, nao apenas a mensagem visivel. Ele interrompe
o elemento de audio atual, revoga os objetos temporarios, limpa frases enfileiradas e invalida
requests TTS que ainda nao terminaram. O estado fica em `localStorage` sob `avento-voice-enabled`.
Trocar de chat tambem interrompe a fala anterior; depois de desmutar, somente frases novas da
conversa atualmente selecionada sao reproduzidas.

## MCP e Automacao Local

O Avento conecta ferramentas locais em camadas:

- Ferramentas internas sempre disponiveis: arquivos autorizados, backup, validacoes seguras, processos controlados, apps/URLs/Shortcuts basicos no macOS.
- `@modelcontextprotocol/server-filesystem` quando ha workspaces autorizados e `npx` esta disponivel.
- `@wonderwhy-er/desktop-commander` para filesystem, terminal, edicao e processos de desenvolvimento mais completos; fica disponivel para conexao manual e nao bloqueia a subida automatica.
- `@steipete/macos-automator-mcp` apenas em macOS, para AppleScript/JXA, Finder, apps, janelas, abas e automacoes nativas.
- `@playwright/mcp@0.0.78` para automacao web via Playwright.
- `@modelcontextprotocol/server-puppeteer@2025.5.12` como alternativa de automacao Chromium.

No primeiro uso, o `npx` pode baixar os pacotes e demorar um pouco. Depois o npm tende a usar cache local. Para desligar uma camada em uma maquina especifica:

```yaml
avento:
  mcp:
    sdk:
      enabled: true
      request-timeout: 10s
    filesystem:
      enabled: false
    desktop-commander:
      enabled: false
    macos-automator:
      enabled: false
    playwright:
      enabled: false
    puppeteer:
      enabled: false
```

Ou por variaveis de ambiente:

```sh
AVENTO_MCP_DESKTOP_COMMANDER_ENABLED=false \
AVENTO_MCP_PLAYWRIGHT_ENABLED=false \
./scripts/dev-up.sh
```

Por padrao, os servidores externos usam o SDK Java oficial do MCP com transporte stdio. Para
diagnosticar uma incompatibilidade temporaria, `AVENTO_MCP_SDK_ENABLED=false` volta ao transporte
legado; `AVENTO_MCP_SDK_REQUEST_TIMEOUT=10s` controla inicializacao e chamadas. Uma conexao repetida
para um servidor ja ativo e idempotente e nao reinicia o processo. Nomes externos que colidem com
ferramentas internas recebem namespace, por exemplo `git__nome_da_tool`.

O caminho de execucao fica centralizado:

1. `LocalAiOrchestratorController` recebe a solicitacao e registra os workspaces validos.
2. `AgentOrchestrator` cria o `runId` e acompanha `RUNNING`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED` ou `CANCELLED`.
3. `AgentService` decide a proxima acao com as skills, intencao, permissoes e contexto disponiveis.
4. `ToolExecutionGateway` executa a ferramenta local ou MCP e valida o resultado antes de devolve-lo ao modelo.

Para inspecao operacional, use `GET /api/ai/runs` para as execucoes recentes e
`GET /api/ai/runs/{runId}` para uma execucao especifica. O stream em `/api/ai/stream` emite
`agent.run.started` com o mesmo `runId`, e aprovacao/rejeicao continuam a execucao original.

Ao chamar `/api/mcp/connect`, o backend retorna tambem um snapshot de ambiente com OS, arquitetura, comandos encontrados (`node`, `npm`, `npx`, `osascript`, `open`, etc.), apps macOS comuns detectados (`Finder`, `Terminal`, `Visual Studio Code`, `Brave Browser`, `Safari`, etc.), MCPs conectados e avisos. O frontend injeta esse bloco no contexto do agente para que o Avento adapte as escolhas quando rodar em outra maquina.

## Seguranca Local

O Avento foi configurado para:

- Escutar em `127.0.0.1` por padrao.
- Bloquear requisicoes nao-loopback quando `avento.security.allow-non-loopback=false`.
- Retornar erros JSON em `401` e `403`, com status, mensagem e path para a interface exibir feedback claro.
- Exigir login local com cookie JWT HttpOnly quando `avento.auth.enabled=true`.
- Validar a sessao somente pelo access JWT em cookie `HttpOnly`, sem expor token de autenticacao ao JavaScript.
- Manter refresh server-side e registrar historico de tokens/acessos.
- Exigir autorizacao da pasta antes de ler ou escrever arquivos.
- Criar backup antes de aplicar mudancas em arquivos.
- Restringir comandos executaveis a uma allowlist local conhecida.

As ferramentas locais essenciais do agente ficam disponiveis pelo backend mesmo quando um servidor MCP externo nao conecta: `directory_tree`, `read_file`, `write_file`, `delete_file`, `create_directory`, `search_files`, `create_vite_project`, `open_app`, `close_app`, `open_url`, `open_path`, `reveal_in_finder`, `run_shortcut`, `capture_screen`, `terminal_run`, `terminal_start`, `terminal_list`, `terminal_logs` e `terminal_stop`. Todas as ferramentas de arquivo exigem que o caminho esteja dentro de um workspace autorizado; `write_file` cria diretorios pais quando necessario e registra backup antes de sobrescrever. `delete_file` remove apenas arquivo regular autorizado e tambem gera backup antes de apagar. `open_path` e `reveal_in_finder` tambem ficam presos a workspaces autorizados. `open_app`, `close_app`, `open_url`, `run_shortcut` e `capture_screen` usam automacao local do macOS sem shell livre; URLs aceitam apenas `http` e `https`, e screenshots sao salvos em `~/Pictures/Avento Screenshots`. `create_vite_project` e um scaffold controlado para templates Vite conhecidos, como `react-ts`, sem liberar shell arbitrario. `terminal_run` tambem nao e shell livre: ele aceita apenas comandos curtos permitidos como `npm create vite@latest ...`, `npm install`, `npm run build/test`, `mvn test`, `git status` e alguns comandos seguros de Docker Compose. Processos longos como `npm run dev` devem usar `terminal_start` e ser acompanhados por `terminal_list`, `terminal_logs` e `terminal_stop`. Quando MCPs externos estiverem conectados, eles aparecem como ferramentas adicionais para o agente; nomes duplicados com ferramentas internas sao ignorados para evitar colisao de rota.

Mensagens casuais curtas, como `oi`, `bom dia`, `tudo bem` ou `como voce esta`, nao recebem contexto de projeto, nao disparam RAG e nao devem acionar ferramentas. O backend tambem ignora tool calls nesse tipo de conversa para evitar chamadas como `directory_tree` sem pedido explicito.

Se um modelo local escrever uma pseudo-chamada textual como `{function <directory_tree> ...}` em vez de usar tool-call nativo, o backend tenta interpretar isso como chamada interna e suprime o markup do stream. Chamadas com `path: "/"` sao rejeitadas com uma mensagem segura, porque ferramentas de arquivo so podem operar dentro de workspaces autorizados.

Ao abrir uma conversa pelo historico, as pastas salvas no contexto do chat sao revalidadas com `/api/fs/authorize` antes de voltarem para o prompt e para as ferramentas. Se uma pasta foi movida ou apagada, ela nao e restaurada como workspace ativo.

Quando uma ferramenta pode alterar arquivos, apagar arquivos, abrir/fechar apps, abrir URLs/arquivos, rodar atalhos, capturar a tela, rodar comandos ou controlar processos (`write_file`, `delete_file`, `create_directory`, `create_vite_project`, `open_app`, `close_app`, `open_url`, `open_path`, `reveal_in_finder`, `run_shortcut`, `capture_screen`, `terminal_run`, `terminal_start`, `terminal_stop`), o agente deve pedir aprovacao antes de executar. A interface mostra um card dentro do balao do Avento com botoes para aprovar, cancelar e enviar comentario opcional; por voz ou texto, tambem e possivel aprovar com frases como `aprovo`, `pode abrir`, `pode executar` ou `pode rodar`.

Para expor em rede local no futuro, trate como uma decisao explicita de produto: habilitar HTTPS, usar segredo JWT forte, revisar CORS, limitar escopo de workspace e auditar acoes do agente.

## Problemas Comuns

Se o backend nao subir:

- Verifique Java 21 com `java -version`.
- Verifique se a porta `8000` esta livre.
- Confira se o Redis esta rodando quando usar RAG.
- Confira se o Ollama esta ativo quando usar chat/embeddings.

Se o frontend nao conectar:

- Confirme que o backend esta em `http://127.0.0.1:8000`.
- Reinicie o Vite depois de alterar variaveis ou dependencias.
- Rode `npm run typecheck` para erros de contrato TypeScript.

Se voz falhar:

- Rode `./scripts/check-local-deps.sh`.
- Ajuste os caminhos no profile local.
- Confirme permissao de execucao dos binarios.
