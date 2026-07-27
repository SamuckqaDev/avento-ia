# Cria um mockup de tela como preview HTML no chat
Gatilhos: mockup, wireframe, prototipo, prototipar, criar prototipo de tela, desenhar interface, visualizar tela, criar layout interativo, prototipar frontend, mockup tela, mockup interface, mockup site, mockup mobile, ui mockup, website mockup, wireframe tela, tela de login, tela de cadastro, tela mobile, interface de

Mockup/wireframe/tela = interface HTML, NUNCA imagem (não use generate_image/ComfyUI).

Pense em UMA linha (objetivo + elementos essenciais da tela) e já construa. Não escreva raciocínio longo.

Entregue um único bloco `ui-preview` com um documento HTML CURTO e autocontido. Regras de tamanho (o modelo é local, HTML grande estoura o limite de contexto e a tela não renderiza):
- CSS curto e limpo: layout, cor, espaçamento. Sem gradientes/animações/sombras decorativas.
- Estático. SEM JavaScript, SEM modais, abas ou lógica — a menos que o usuário peça explicitamente.
- Placeholders (blocos de cor / texto) no lugar de imagens. Nada de CDN, fonte ou asset externo.
- Só a estrutura e o visual essencial da tela pedida. Enxuto de propósito.

Formato: gere o bloco `ui-preview` como conteúdo da resposta, solto no nível raiz do Markdown. NUNCA coloque este bloco dentro de `<think>...</think>` — o raciocínio fica no thinking, o bloco fica na resposta visível, senão o preview não aparece.

```ui-preview
<!doctype html>
<html lang="pt-BR">
  ...
</html>
```

Fora do bloco, escreva no máximo uma frase e pergunte se quer ajustar. Só traduza o mockup para o código real do projeto depois de aprovação explícita do usuário.
