# Mantém projetos Java com arquitetura em camadas, DTOs, Lombok e SOLID
Gatilhos: manutencao java, manutenção java, refatorar service java, revisar arquitetura java, clean code java, solid java, corrigir projeto spring, refatorar projeto spring

Use este procedimento ao criar, corrigir, revisar ou refatorar Java e Spring:

1. Inspecione os packages, contratos, testes e configurações relacionados antes de editar.
2. Preserve a direção `controller -> service/application -> domain -> repository/integration`.
3. Use DTOs de request e response em controllers e DTOs próprios para eventos, filas e providers.
   Nunca exponha entidade JPA diretamente. Prefira `record` para DTO imutável.
4. Injete dependências somente por construtor e mantenha-as `final`. Quando Lombok estiver disponível,
   prefira `@RequiredArgsConstructor` nos componentes Spring.
5. Use Lombok para remover boilerplate: `@Getter` e setters restritos em entidades, `@Value` e
   `@Builder` em contratos adequados. Nunca use `@Data` em entidade JPA.
6. Mantenha controller fino. Regras de negócio ficam no domínio ou no service do caso de uso;
   repository apenas persiste e integração apenas adapta providers.
7. Se um service misturar responsabilidades, extraia colaboradores coesos por capacidade. Não crie
   classes minúsculas apenas para reduzir linhas e não esconda responsabilidades em `Utils`.
8. Coloque transações no caso de uso, valide ownership no backend, não registre segredos e traduza
   erros na fronteira HTTP.
9. Antes de remover legado, procure referências em código, testes, configuração e documentação.
   Remova implementação, testes e configuração obsoletos juntos somente com evidência.
10. Adicione testes focados, execute os comandos reais do projeto e atualize a documentação quando
    comportamento, API, arquitetura, persistência ou setup mudarem.

Checklist final:
- DTOs protegem todas as fronteiras externas.
- Dependências obrigatórias estão explícitas no construtor.
- Cada service tem uma responsabilidade coerente.
- SOLID reduziu acoplamento sem criar abstração vazia.
- Imports ficam no topo e não há classe com nome totalmente qualificado no meio do código.
- Testes e documentação correspondem ao resultado real.

Não faça uma refatoração ampla fora do pedido atual. Relate débitos maiores e trate-os em uma
mudança dedicada para não misturar risco arquitetural com uma correção pequena.
