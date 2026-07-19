# Cria um projeto Vite com o template pedido dentro do workspace autorizado
Gatilhos: criar projeto vite, crie projeto vite, cria projeto vite, novo projeto vite, scaffold vite

Use create_vite_project com path, name e template extraídos do pedido.

Regras:
- Templates aceitos incluem react-ts, react, vue-ts, vue, svelte-ts, svelte, solid-ts, solid, preact-ts, preact, lit-ts, lit, vanilla-ts e vanilla.
- Se o usuário não indicar template, use react-ts.
- Não substitua uma pasta existente sem confirmação explícita.
- Depois do scaffold, leia o resultado real. Só execute npm install ou validações se isso fizer parte do pedido.
- Nunca use write_file para imitar o scaffold.
