#!/usr/bin/env bash
#
# Despacha uma spec pro Codex executar — sem GUI, sem clique.
#
# Uso:
#   ./tools/codex-task.sh docs/agent-tasks/<slug>.md
#   ./tools/codex-task.sh docs/agent-tasks/<slug>.md --read-only    # só analisa, não escreve
#   CODEX_EFFORT=high ./tools/codex-task.sh docs/agent-tasks/<slug>.md
#
# O binário NÃO está no PATH. O Codex instala a CLI dentro de
# ~/.codex/plugins/.plugin-appserver/codex — descobrir isso custa tempo, então o script
# procura ali primeiro e só depois no PATH.

set -euo pipefail

SPEC="${1:-}"
shift || true

CODEX_BIN=""
for candidate in \
    "$HOME/.codex/plugins/.plugin-appserver/codex" \
    "$(command -v codex 2>/dev/null || true)"; do
  if [[ -n "$candidate" && -x "$candidate" ]]; then CODEX_BIN="$candidate"; break; fi
done

die() { echo "erro: $*" >&2; exit 1; }

[[ -n "$CODEX_BIN" ]] || die "CLI do Codex nao encontrada. Procurei em ~/.codex/plugins/.plugin-appserver/codex e no PATH."
[[ -n "$SPEC" ]] || die "informe a spec: $0 docs/agent-tasks/<slug>.md"
[[ -f "$SPEC" ]] || die "spec nao encontrada: $SPEC"

SPEC_ABS="$(cd "$(dirname "$SPEC")" && pwd)/$(basename "$SPEC")"
REPO_ROOT="$(git -C "$(dirname "$SPEC_ABS")" rev-parse --show-toplevel 2>/dev/null || true)"
[[ -n "$REPO_ROOT" ]] || die "a spec precisa estar dentro de um repositorio git"

# workspace-write por padrao: a tarefa e executar, e executar significa escrever no repo.
# read-only serve para pedir analise sem deixar o agente tocar em nada.
SANDBOX="workspace-write"
for arg in "$@"; do
  [[ "$arg" == "--read-only" ]] && SANDBOX="read-only"
done

# O config.toml do usuario costuma vir com esforco baixo, que serve para pergunta rapida e nao
# para migracao com armadilha. Spec longa merece esforco alto; sobrescreve so nesta invocacao.
EFFORT="${CODEX_EFFORT:-high}"

BRANCH="$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"

echo "spec    : $SPEC_ABS"
echo "repo    : $REPO_ROOT"
echo "branch  : $BRANCH"
echo "sandbox : $SANDBOX"
echo "esforco : $EFFORT"
echo "---"

# A spec vai pelo stdin em vez de argumento: specs passam de 8 KB e argumento tem limite.
# O caminho absoluto vai junto porque o agente precisa poder reler o arquivo enquanto trabalha.
{
  echo "Leia e execute a spec em $SPEC_ABS."
  echo
  echo "Confirme que esta na branch '$BRANCH' antes de comecar; se nao estiver, pare e reporte."
  if [[ "$SANDBOX" == "read-only" ]]; then
    # Sem esta ressalva o agente tenta rodar a suite, o sandbox recusa a escrita em target/, e
    # ele queima tokens contornando um bloqueio que e proposital. Medido na primeira execucao.
    echo "MODO SOMENTE LEITURA: nao tente rodar build nem testes — eles precisam escrever em"
    echo "target/ e o sandbox vai recusar. Analise e reporte."
  else
    echo "Rode a suite de testes ao fim de cada tarefa da spec. Se um teste que passava antes"
    echo "falhar, PARE e reporte em vez de ajustar o teste."
  fi
  echo
  echo "--- CONTEUDO DA SPEC ---"
  cat "$SPEC_ABS"
} | "$CODEX_BIN" exec \
      -C "$REPO_ROOT" \
      -s "$SANDBOX" \
      -c model_reasoning_effort="\"$EFFORT\"" \
      -
