# Teste Espacial de 1 Minuto - Sistema Avento

## 🎯 Objetivo

Executar uma sequência rápida de verificações para diagnosticar o estado do sistema, monitorando efeitos visuais e respostas em tempo real.

## ⚡ Características

- **Tempo total**: ~60 segundos
- **Efeitos visuais**: Monitoramento em tempo real com feedback visual
- **Diagnóstico completo**: Verifica todos os componentes críticos
- **Relatório conciso**: Resultados formatados de forma clara

## 📋 O que é verificado

### 1. Serviços do Sistema
- ✅ PostgreSQL (banco de dados)
- ✅ Redis (cache e filas)
- ✅ Autenticação JWT
- ✅ Recursos do sistema (CPU, RAM, Rede)

### 2. Efeitos Visuais
- Renderização inicial em tempo real
- Aplicação de efeitos visuais
- Monitoramento de performance visual

### 3. Persistência de Dados
- Conexão com banco de dados
- Schema migrado corretamente
- Dados persistidos adequadamente

## 🚀 Como Executar

### Via Console (Aplicação em execução)

```bash
# O teste é executado automaticamente no startup
java -jar avento-app.jar
```

O output mostrará:
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
  🌐 Rede: OK

⚡ TESTE DE EFEITO VISUAL - RENDERIZAÇÃO EM TEMPO REAL...

  ⚡ Renderização inicial...
  ✅ Visualização carregada com sucesso!
```

### Via API REST

#### Executar Teste Completo

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

#### Verificar Status do Sistema

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

## 📊 Output Esperado

### Exemplo de Saída Completa:

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

## 🔧 Configuração

### No `pom.xml` (já configurado)

O teste é executado automaticamente via `CommandLineRunner`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Componentes Implementados

1. **TesteEspacial.java** - Execução automática no startup
2. **TesteEspacialController.java** - Endpoints REST API
3. **Documentação** - Este arquivo de referência

## 🎨 Efeitos Visuais Monitorados

- ✅ Renderização inicial em tempo real
- ✅ Aplicação de efeitos visuais (CSS, animações)
- ✅ Performance da interface gráfica
- ✅ Responsividade do sistema visual

## 🔍 Diagnóstico Realizado

### Serviços Verificados:
1. **PostgreSQL** - Banco de dados principal
2. **Redis** - Cache e filas assíncronas
3. **Autenticação JWT** - Tokens válidos
4. **Recursos do Sistema** - CPU, RAM, Rede

### Performance Monitorada:
- Tempo de resposta dos serviços
- Latência da rede
- Utilização de recursos
- Efeitos visuais aplicados

## 📝 Notas Importantes

1. O teste é executado automaticamente no startup da aplicação Spring Boot
2. Os endpoints REST permitem execução manual para diagnóstico remoto
3. Todos os resultados são formatados com emojis e ícones para fácil leitura
4. O tempo de execução varia conforme a carga do sistema

## 🚀 Próximos Passos

- [ ] Integrar com monitoramento externo (Prometheus, Grafana)
- [ ] Adicionar métricas detalhadas em JSON
- [ ] Criar dashboard visual dos resultados
- [ ] Implementar alertas automáticos para falhas detectadas

## 📞 Suporte

Para problemas ou dúvidas sobre o Teste Espacial:
- Verifique os logs do sistema (`/logs`)
- Execute manualmente via API REST
- Consulte a documentação completa em `/docs`

---

**Versão**: 1.0  
**Última atualização**: 2026-07-26  
**Status**: ✅ Ativo e Operacional
