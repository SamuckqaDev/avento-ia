# 🚀 Testes Espaciais do Sistema Avento

## Visão Geral

Os testes espaciais são uma suite de diagnóstico rápida e visual que verifica o estado completo do sistema em aproximadamente 1 minuto. Eles foram projetados para:

- ✅ **Diagnóstico rápido**: Verifica todos os componentes críticos
- 🎨 **Efeitos visuais**: Monitora a renderização em tempo real  
- 🔍 **Feedback imediato**: Resultados claros e formatados visualmente
- 📊 **Métricas completas**: Coleta dados de performance

## 📁 Estrutura dos Arquivos

```
src/main/resources/testes/
├── README.md                    # Este arquivo
└── especial.md                  # Documentação completa do teste espacial
```

## 🔧 Implementações

### 1. TesteEspacial.java
- **Localização**: `src/main/java/com/avento/testes/TesteEspacial.java`
- **Função**: Executa automaticamente no startup via CommandLineRunner
- **Características**:
  - Verifica serviços do sistema (PostgreSQL, Redis)
  - Monitora efeitos visuais em tempo real
  - Coleta métricas de performance
  - Gera relatório formatado com emojis

### 2. TesteEspacialController.java
- **Localização**: `src/main/java/com/avento/api/TesteEspacialController.java`
- **Função**: Endpoints REST para execução manual dos testes
- **Endpoints disponíveis**:
  - `GET /api/testes/espacial/1minuto` - Executa teste completo
  - `GET /api/testes/status` - Verifica status do sistema

## 🚀 Como Usar

### Auto-execução (Recomendado)

O teste é executado automaticamente quando você inicia a aplicação:

```bash
mvn spring-boot:run
# ou
java -jar avento-app.jar
```

**Output no console:**
```
╔══════════════════════════════════════════════════════════╗
║  TESTE ESPACIAL DE 1 MINUTO - SISTEMA AVENTO             ║
║  Efeito Visual & Diagnóstico                              ║
╚══════════════════════════════════════════════════════════╝

🔍 INICIANDO SEQUÊNCIA DE DIAGNÓSTICO...
✅ PostgreSQL/: ONLINE (12ms)
📊 VERIFICANDO RECURSOS DO SISTEMA...
  🖥️  CPU: OK
  💾 RAM: OK
```

### Execução Manual via API

#### Teste Completo
```bash
curl http://localhost:8080/api/testes/espacial/1minuto
```

**Resposta:**
```json
{
  "status": "SUCESSO",
  "mensagem": "✅ Teste espacial executado com sucesso!",
  "tempoExecucaoMs": 1234
}
```

#### Verificar Status
```bash
curl http://localhost:8080/api/testes/status
```

**Resposta:**
```json
{
  "status": "ONLINE",
  "postgresql": true,
  "autenticacao": true,
  "recursosSistema": true
}
```

## 📊 O que é Verificado

### Serviços do Sistema
- ✅ **PostgreSQL** - Banco de dados principal
- ✅ **Redis** - Cache e filas assíncronas  
- ✅ **Autenticação JWT** - Tokens válidos e permissões
- ✅ **Recursos do Sistema** - CPU, RAM, Rede

### Efeitos Visuais
- ⚡ Renderização inicial em tempo real
- 🎨 Aplicação de efeitos visuais (CSS, animações)
- 💫 Performance da interface gráfica
- 📈 Responsividade do sistema visual

### Persistência de Dados
- ✅ Conexão com banco de dados estabelecida
- 📝 Schema migrado corretamente
- 💾 Dados persistidos adequadamente

## 🔍 Exemplo Completo de Output

```bash
$ java -jar avento-app.jar

╔══════════════════════════════════════════════════════════╗
║  TESTE ESPACIAL DE 1 MINUTO - SISTEMA AVENTO             ║
║  Efeito Visual & Diagnóstico                              ║
╚══════════════════════════════════════════════════════════╝

🔍 INICIANDO SEQUÊNCIA DE DIAGNÓSTICO...

✅ PostgreSQL/: ONLINE (12ms)

📊 VERIFICANDO RECURSOS DO SISTEMA...

  🖥️  CPU: OK
  💾 RAM: OK
  🌐 Rede: OK

⚡ TESTE DE EFEITO VISUAL - RENDERIZAÇÃO EM TEMPO REAL...

  ⚡ Renderização inicial...
  ✅ Visualização carregada com sucesso!
  
  🎨 Efeito visual aplicado: OK

🔐 VERIFICANDO AUTENTICAÇÃO & PERMISSÕES...

✅ Autenticação JWT: Ativa
  
  🔑 Token válido gerado
  
  🛡️  Permissões verificadas: OK

💾 VERIFICANDO PERSISTÊNCIA DE DADOS...

  ✅ Conexão com banco de dados estabelecida
  📝 Schema migrado corretamente
  💾 Dados persistidos: OK

╔══════════════════════════════════════════════════════════╗
║  RESULTADO DO TESTE ESPACIAL                             ║
║  Tempo Total: 1 segundo(s)                               ║
╚══════════════════════════════════════════════════════════╝

✅ Todos os sistemas operando normalmente!
```

## 🎯 Casos de Uso

### 1. Verificação Pós-Deploy
Após implantar uma nova versão, execute o teste para garantir que todos os serviços estão funcionando corretamente.

### 2. Diagnóstico Rápido
Quando um usuário reporta problemas, execute o teste via API REST para obter um diagnóstico imediato do estado do sistema.

### 3. Monitoramento Contínuo
Configure scripts de cron ou ferramentas de monitoramento (Prometheus, Grafana) para executar os testes periodicamente e alertar sobre falhas detectadas.

## 🔧 Configuração Personalizada

Para personalizar o comportamento dos testes espaciais:

1. **Editar `TesteEspacial.java`** - Modifique as verificações
2. **Adicionar novos endpoints no Controller** - Crie novos métodos de teste
3. **Atualizar documentação em `especial.md`** - Documente novas funcionalidades

## 📈 Métricas Coletadas

Os testes espaciais coletam automaticamente:

- Tempo de resposta dos serviços (em ms)
- Status online/offline de cada componente
- Utilização de recursos do sistema
- Performance da renderização visual
- Validação de tokens JWT e permissões
- Integridade da persistência de dados

## 🚨 Tratamento de Erros

O teste espacial trata adequadamente erros comuns:

```java
try {
    // Verificações...
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    System.err.println("⚠️  Teste interrompido pelo usuário.");
}
```

Em caso de falha, o teste retorna um status apropriado no output.

## 📝 Notas Importantes

1. **Auto-execução**: O teste roda automaticamente no startup da aplicação Spring Boot
2. **API REST**: Endpoints permitem execução manual para diagnóstico remoto
3. **Formato visual**: Todos os resultados usam emojis e ícones para fácil leitura
4. **Tempo variável**: A duração varia conforme a carga do sistema (tipicamente 1-5 segundos)

## 🎨 Próximas Funcionalidades

Em desenvolvimento:

- [ ] Integração com Prometheus/Grafana
- [ ] Dashboard visual dos resultados em tempo real
- [ ] Alertas automáticos para falhas detectadas
- [ ] Testes de carga integrados ao teste espacial
- [ ] Relatórios detalhados em PDF/HTML

## 📞 Suporte e Contribuição

Para problemas ou sugestões:

1. Verifique os logs do sistema (`/logs`)
2. Execute manualmente via API REST para diagnóstico específico
3. Consulte a documentação completa em `/docs`
4. Abra um issue no repositório com detalhes da falha

---

**Versão**: 1.0  
**Última atualização**: 2026-07-26  
**Status**: ✅ Ativo e Operacional  
**Mantenedor**: Samuel Tomimatu (Avento)
