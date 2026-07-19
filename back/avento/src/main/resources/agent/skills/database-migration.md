# Cria e valida migrações PostgreSQL seguras no Avento
Gatilhos: criar migracao banco, criar migração banco, alterar schema postgres, migracao flyway, migração flyway

1. Inspecione migrations, entidades, repositories, constraints e consultas afetadas.
2. Crie uma nova migration Flyway imutável; nunca altere uma migration já aplicada.
3. Para mudanças arriscadas, use etapas aditivas: adicionar, preencher, validar, trocar uso e remover depois.
4. Expresse invariantes com foreign keys, unicidade, nullability e índices alinhados às consultas reais.
5. Mantenha PostgreSQL como verdade durável e Redis como dado reconstruível.
6. Alinhe mappings JPA sem expor entidades em controllers, eventos ou integrações.
7. Teste banco vazio, upgrade da versão anterior e queries dos repositories afetados.

Em exclusões permanentes, confira o grafo completo de chats, mensagens, documentos, vetores e mídias no disco.
