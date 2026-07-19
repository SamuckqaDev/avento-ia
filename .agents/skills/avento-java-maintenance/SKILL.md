---
name: avento-java-maintenance
description: Apply the Avento Java engineering standard whenever creating, changing, reviewing, fixing, or refactoring Java/Spring code in this repository. Enforces layered architecture, DTO boundaries, constructor injection, careful Lombok usage, SOLID decomposition, small services, explicit transactions, tests, documentation, and removal of proven dead code.
---

# Avento Java Maintenance

Use this skill for every Java change in Avento, including controllers, services, repositories,
entities, configuration, integrations, tests, and architecture reviews.

## Required workflow

1. Read the related package, tests, configuration, and public contracts before editing.
2. Read [the detailed Java standard](references/java-standards.md).
3. Identify the owning layer and keep dependencies pointing inward.
4. Define or reuse request/response DTOs at every HTTP, event, queue, MCP, or provider boundary.
5. Inject dependencies through constructors. For Spring components, prefer final fields with
   Lombok `@RequiredArgsConstructor`.
6. Use Lombok where it removes mechanical boilerplate without hiding domain behavior.
7. If a service owns unrelated responsibilities, extract cohesive collaborators by use case or
   capability before adding more branches to it.
8. Remove old code only after proving it has no runtime, test, documentation, or migration use.
9. Add focused tests at the changed boundary and run the broader Java validation when risk warrants.
10. Update project documentation when architecture, setup, behavior, API, persistence, or operation
    changes.

## Non-negotiable rules

- Never expose JPA entities directly through controllers, events, queues, or integrations.
- Never use field injection or mutable injected dependencies.
- Never add `@Data` to JPA entities.
- Never put business rules in controllers, repositories, DTOs, or mappers.
- Never create a generic helper or `Utils` class to hide unrelated responsibilities.
- Never keep two active implementations without an explicit compatibility reason and removal plan.
- Never claim SOLID by increasing indirection; each extraction must create a real ownership boundary.
- Keep imports explicit and at the top; do not use inline fully qualified class names.

## Completion gate

Before finishing, confirm:

- DTOs isolate external contracts from persistence models.
- constructors and fields make required dependencies explicit;
- package and dependency direction match the layered architecture;
- services have one coherent reason to change;
- exceptions, transactions, logs, and nullability are intentional;
- tests cover the changed behavior;
- dead code and obsolete configuration discovered in scope are removed;
- documentation matches the code that now exists.
