# Prototipacao local de interfaces

O Avento pode desenhar uma tela como HTML interativo dentro da propria conversa. Esse caminho nao
gera PNG, nao chama o ComfyUI e nao precisa manter um modelo visual carregado. O navegador renderiza
o resultado e permite revisar layout, conteudo, responsividade e interacoes antes de alterar o
projeto conectado.

## Como usar

1. Descreva a tela e o publico que vai utiliza-la. Por exemplo: `crie um mockup da tela de login`
   ou `crie um prototipo de tela para acompanhar jobs de imagem e video`. Os termos `mockup de
   tela`, `mockup de interface`, `ui mockup`, `website mockup` e `wireframe de tela` ativam este
   fluxo antes do roteamento de geracao de imagens.
2. O gatilho ativa a skill `prototype-interface`. Ela devolve um documento HTML completo em um
   bloco `ui-preview`.
3. O resultado aparece recolhido em uma miniatura para nao ocupar a conversa. Clique na miniatura
   ou use Enter/Espaco quando ela estiver em foco para abrir o mockup dentro do chat.
4. Use os icones no card para alternar entre desktop, tablet e celular.
5. Expanda a previa para examinar a interface em uma area maior. Scripts ficam desligados por
   padrao; use o controle de interacao somente quando quiser testar botoes, abas ou modais.
6. Peca mudancas na mesma conversa. Cada resposta permanece no historico, permitindo comparar as
   propostas.
7. Quando o desenho estiver correto, aprove explicitamente a implementacao. Somente nessa etapa o
   agente deve alterar os arquivos do workspace.
8. Depois da implementacao, use Playwright para validar a pagina real nos mesmos tamanhos.

Tambem e possivel ativar o procedimento diretamente:

```text
/prototype-interface crie uma tela compacta para acompanhar processos locais
```

## Formato do artefato

O visualizador reconhece este bloco dentro de uma mensagem persistida:

````markdown
```ui-preview
<!doctype html>
<html lang="pt-BR">
  <head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Painel de processos</title>
    <style>/* estilos autocontidos */</style>
  </head>
  <body>...</body>
</html>
```
````

O HTML fica no conteudo normal da mensagem. Por isso ele acompanha o chat no PostgreSQL sem tabela,
arquivo de midia ou job adicional.

Mockups de interface nao chamam `generate_image`, ComfyUI ou outro gerador visual. Uma solicitacao
que use apenas a palavra `mockup` sem indicar tela, site, interface ou wireframe continua disponivel
para outros fluxos; a intencao de interface precisa estar presente para evitar falsos positivos.

## Isolamento e limites

O documento roda em um `iframe` com origem isolada. Scripts ficam desativados por padrao; quando o
usuario habilita interacoes, o sandbox recebe somente `allow-scripts`, sem acesso a mesma origem,
popups ou janela principal. O frontend tambem injeta uma Content Security Policy que bloqueia
chamadas de rede, formularios externos, objetos e frames. A previa aceita estilos e scripts inline e
assets `data:` ou `blob:`.

Esse isolamento permite demonstrar interacoes locais, mas deliberadamente impede:

- chamar APIs ou carregar bibliotecas por CDN;
- acessar cookies, sessao ou DOM do Avento;
- navegar a janela principal;
- representar integracoes reais com o backend.

Depois da aprovacao, as integracoes devem ser implementadas no projeto usando seus componentes,
servicos, DTOs, autenticacao e regras reais. O prototipo e uma referencia executavel, nao codigo de
producao automatico.

## Consumo de recursos

A geracao do prototipo usa o mesmo modelo de conversa e produz texto. A renderizacao e feita pelo
navegador e normalmente consome muito menos memoria que um workflow de difusao. ComfyUI, checkpoint,
VAE e modelos de controle nao participam desse fluxo. Um modelo visual continua opcional para uma
auditoria por screenshot; a arvore DOM e a acessibilidade do Playwright costumam ser suficientes para
revisar estrutura e comportamento.
