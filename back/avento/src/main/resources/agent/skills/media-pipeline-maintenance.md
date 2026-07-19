# Diagnostica e mantém geração local de imagem e vídeo pelo ComfyUI

1. Identifique operação, provider/modelo, workflow, mídia de referência e opções do frontend.
2. Verifique health do ComfyUI, nós, checkpoints, encoders, VAE e memória antes de enfileirar.
3. Mapeie opções tipadas para inputs dos nós; não altere workflow JSON com substituição global frágil.
4. Execute como job assíncrono com `runId`, progresso, cancelamento, erro terminal e metadados.
5. Retorne apenas arquivos realmente produzidos e verificados; texto do modelo nunca é sucesso.
6. Guarde ownership e caminhos no PostgreSQL e entregue preview por endpoint autorizado.
7. Ao apagar o chat, remova registros, thumbnails, arquivos e resíduos do provider.
8. Teste draft determinístico antes de qualidade, referência, refinamento e vídeo.

Mantenha enhancement opcional e preserve sujeito, quantidade, composição e referência pedidos.
