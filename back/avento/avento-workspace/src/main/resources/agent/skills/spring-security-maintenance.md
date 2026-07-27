# Mantém autenticação e autorização Spring Security com JWT em cookie
Gatilhos: corrigir spring security, problema de login, problema de logout, revalidacao de sessao, revalidação de sessão, jwt cookie

1. Rastreie SecurityConfig, filtro JWT, DTOs, AuthService, sessões no PostgreSQL e cliente Axios.
2. Use apenas o cookie HttpOnly configurado como credencial no navegador; não crie uma segunda fonte em localStorage.
3. Trate login, refresh, validação e logout como transições explícitas. Logout revoga a sessão e limpa o cookie.
4. Valide ownership e permissão no backend para todo recurso protegido.
5. Preserve cookie utilizável em HTTP local de desenvolvimento e propriedades seguras configuráveis para produção.
6. Não registre senha, cookie, JWT ou refresh token. Use eventos de auditoria sem segredos.
7. Teste token válido, ausente, expirado, revogado, malformado, usuário diferente e corrida refresh/logout.

Não enfraqueça a segurança para esconder uma falha de contrato.
