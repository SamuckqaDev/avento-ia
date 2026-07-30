import { describe, expect, it } from 'vitest';
import { toAccountEmail } from './AuthProvider';

/**
 * O login curto ("root") existe porque a conta local se chama assim, mas o servidor identifica
 * usuário por email e valida `@Email`. Um erro aqui volta como HTTP 400 de validação, que não diz
 * nada sobre a causa — daí valer a pena prender o comportamento.
 */
describe('toAccountEmail', () => {
  it('completa o domínio local quando só o usuário é digitado', () => {
    expect(toAccountEmail('root')).toBe('root@avento.local');
  });

  it('não toca em quem já digitou o email inteiro', () => {
    expect(toAccountEmail('dev.matsutech@gmail.com')).toBe('dev.matsutech@gmail.com');
    expect(toAccountEmail('admin@avento.local')).toBe('admin@avento.local');
  });

  it('ignora espaços em volta, que o preenchimento automático costuma deixar', () => {
    expect(toAccountEmail('  root  ')).toBe('root@avento.local');
    expect(toAccountEmail(' admin@avento.local ')).toBe('admin@avento.local');
  });

  // O email gravado pelo seeder é minúsculo (normalizeEmail); "ROOT" precisa casar com ele.
  it('normaliza a caixa do usuário curto', () => {
    expect(toAccountEmail('ROOT')).toBe('root@avento.local');
  });

  it('deixa o campo vazio como está, para a validação do formulário reclamar', () => {
    expect(toAccountEmail('   ')).toBe('');
  });
});
