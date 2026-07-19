# Cria e revisa interfaces locais interativas antes de alterar o projeto
Gatilhos: criar prototipo de tela, desenhar interface, visualizar tela, criar layout interativo, prototipar frontend, mockup tela, mockup interface, mockup site, ui mockup, website mockup, wireframe tela

Use esta skill quando o usuario quiser pensar, desenhar ou revisar uma interface antes da implementacao.

1. Trate mockup, wireframe ou prototipo de tela como interface HTML executavel. Nao chame `generate_image`, ComfyUI ou outro gerador visual para esse tipo de pedido.
2. Entregue a proposta em um unico bloco `ui-preview` contendo um documento HTML completo.
3. Inclua `<title>`, viewport, HTML semantico, CSS responsivo e JavaScript apenas quando a interacao precisar dele.
4. Use somente HTML, CSS, JavaScript e assets `data:` autocontidos. Nao carregue CDN, fonte, script, imagem ou API externa.
5. Preserve o stack e os padroes visuais informados pelo usuario; o prototipo nao autoriza adicionar Tailwind ou outra dependencia ao projeto.
6. Deixe controles demonstraveis: navegacao, menus, formularios, abas, modais e estados relevantes devem responder dentro da previa.
7. A previa aparece primeiro como miniatura recolhida no chat. O usuario pode expandi-la, alternar o dispositivo, abrir o modal e habilitar interacoes.
8. Depois de mostrar a previa, discuta alteracoes com o usuario e atualize o mesmo artefato. Nao edite o projeto ate receber aprovacao explicita para implementar.
9. Quando aprovado, inspecione o repositorio, traduza o prototipo para os componentes e estilos reais e valide a pagina com Playwright nos tamanhos desktop, tablet e celular.
10. Se Playwright estiver conectado, use a arvore de acessibilidade e o DOM da previa para revisar estrutura e comportamento. Screenshot e modelo visual sao opcionais, nunca requisito para produzir a tela.

Formato obrigatorio:

````text
```ui-preview
<!doctype html>
<html lang="pt-BR">
  ...
</html>
```
````

Fora do bloco, escreva apenas um resumo curto das decisoes e a pergunta objetiva que precisa da avaliacao do usuario.
