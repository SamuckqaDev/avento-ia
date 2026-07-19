# Avento Java engineering standard

## Layer direction

Use this dependency direction:

`controller/transport -> application/service -> domain -> repository/integration`

- **Controller/transport:** validates transport input, obtains authentication context, maps status
  codes, and delegates. It contains no business workflow.
- **Application/service:** owns a use case and transaction boundary. It coordinates domain logic and
  ports but does not know HTTP details.
- **Domain:** contains business concepts and invariants. Keep it independent from controllers and
  provider payloads.
- **Repository/integration:** implements persistence and external providers. Provider-specific DTOs
  do not leak into the application layer.

Prefer feature-oriented packages inside these boundaries when a capability grows. Do not create a
layer containing unrelated classes only to satisfy a folder diagram.

## DTO boundaries

Use DTOs for:

- controller requests and responses;
- Redis Stream and queue envelopes;
- MCP, Ollama, ComfyUI, database discovery, and other provider contracts;
- commands and results passed between independently evolving modules.

Prefer immutable Java `record` DTOs for simple contracts. Use Lombok `@Value` and `@Builder` when a
contract needs a builder or cannot be a record. Add Jakarta validation to request DTOs and map DTOs
at the boundary. Never return entities directly. Do not create pass-through DTOs between private
methods inside one cohesive class.

## Lombok policy

- Spring component: `@RequiredArgsConstructor` plus `private final` dependencies.
- Immutable value object: prefer a Java record; otherwise `@Value`.
- Complex DTO construction: `@Builder` on the DTO, not on mutable entities by default.
- JPA entity: conservative `@Getter`; add narrow `@Setter` only where ORM/application mutation needs
  it. A protected no-args constructor may use `@NoArgsConstructor(access = PROTECTED)`.
- Configuration properties: `@Getter` and `@Setter` are acceptable for binder-managed values.
- Avoid `@Data` on entities and avoid generated `toString`, `equals`, or `hashCode` across lazy JPA
  relationships.
- Do not add Lombok when the handwritten method enforces an invariant or communicates domain intent.

## Constructor injection

Dependencies are required, immutable constructor parameters. Do not use `@Autowired` fields or
setters. Optional behavior should normally be represented by a dedicated strategy, an
`ObjectProvider`, or an explicit optional port, not nullable constructor arguments scattered
through production code. Test-only convenience constructors must not weaken the production
contract.

## Service decomposition

A service should represent one use case or one cohesive capability. Review it for extraction when:

- its methods serve unrelated actors or workflows;
- it mixes orchestration, persistence, parsing, provider calls, mapping, and presentation;
- a new change repeatedly touches distant sections of the same file;
- tests require many unrelated mocks;
- private branches form a second subsystem;
- its name becomes generic, such as `Manager`, `Helper`, or `Utils`.

Extract by responsibility, for example `RunSubmissionService`, `RunWorker`, `EventPublisher`, and
`ContextCache`. Do not split one linear algorithm into tiny classes solely to reduce line count.
Keep public methods small enough to show the use-case flow; move meaningful policies into named
collaborators rather than chains of low-value private wrappers.

## SOLID review

- **SRP:** one cohesive reason to change per class.
- **OCP:** add strategies/providers behind an interface when there are genuine variants.
- **LSP:** implementations preserve the semantic contract, including errors and side effects.
- **ISP:** expose narrow ports instead of a large interface used partially by every caller.
- **DIP:** application logic depends on ports; infrastructure implements them.

Prefer composition. Add an interface only for a real boundary, variant, or test seam. An interface
with one permanent implementation is not automatically cleaner.

## Persistence and transactions

- Put transaction boundaries on application use cases.
- Keep entities inside the persistence/application boundary.
- Use explicit repository queries and indexes for real access patterns.
- Avoid N+1 queries and disable Open Session in View when DTO mapping is fully controlled.
- Use versioned migrations for distributable schemas; `ddl-auto:update` is development-only.
- Keep PostgreSQL as durable truth and Redis data reconstructible unless a documented design says
  otherwise.

## Errors, logs, and security

- Translate domain/application failures once at the transport boundary.
- Do not swallow exceptions without a deliberate fallback and useful log context.
- Never log secrets, cookies, tokens, passwords, base64 payloads, or full sensitive provider data.
- Use structured identifiers such as `userId`, `chatId`, `runId`, and `jobId` in operational logs.
- Validate ownership in the backend even when the frontend already filtered the action.

## Tests and maintenance

- Unit-test domain policy and isolated application behavior.
- Integration-test repository mappings, security filters, serialization, Redis contracts, and
  provider adapters where those contracts can fail independently.
- Test the bug or behavior being changed before broad cleanup.
- Search references before deleting code. Remove dead implementation, tests, configuration, proxy
  routes, assets, and documentation together.
- Run compile/tests plus repository-specific format and static checks. Existing unrelated failures
  must be reported, not silently reformatted or reverted.
