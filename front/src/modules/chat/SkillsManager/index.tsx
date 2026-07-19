import { useMemo, useState } from 'react';
import { MagnifyingGlass, Trash, X } from '@phosphor-icons/react';
import type { Skill, CreateSkillInput } from '../../../hooks/useSkills';
import {
  Backdrop,
  Modal,
  CloseButton,
  ScrollArea,
  SkillDirectory,
  SkillDirectoryHeader,
  SkillSearch,
  SkillListViewport,
  SkillList,
  SkillRow,
  SkillBadge,
  DeleteSkillButton,
  FormSection,
  FormError,
  FormActions,
  SaveButton,
} from './styles';

interface SkillsManagerProps {
  skills: Skill[];
  onClose: () => void;
  onCreate: (input: CreateSkillInput) => Promise<string | null>;
  onDelete: (name: string) => Promise<string | null>;
}

export function SkillsManager({ skills, onClose, onCreate, onDelete }: SkillsManagerProps) {
  const [search, setSearch] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [triggersText, setTriggersText] = useState('');
  const [body, setBody] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);

  const filteredSkills = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('pt-BR');
    if (!query) return skills;

    return skills.filter(skill => (
      skill.name.toLocaleLowerCase('pt-BR').includes(query)
      || skill.description.toLocaleLowerCase('pt-BR').includes(query)
      || skill.triggers.some(trigger => trigger.toLocaleLowerCase('pt-BR').includes(query))
    ));
  }, [search, skills]);

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setIsSaving(true);
    const failure = await onCreate({
      name: name.trim().toLowerCase(),
      description: description.trim(),
      triggers: triggersText
        .split(',')
        .map(trigger => trigger.trim())
        .filter(Boolean),
      body: body.trim(),
    });
    setIsSaving(false);
    if (failure) {
      setError(failure);
      return;
    }
    setName('');
    setDescription('');
    setTriggersText('');
    setBody('');
  };

  return (
    <Backdrop onClick={onClose}>
      <Modal onClick={(event) => event.stopPropagation()} role="dialog" aria-label="Gerenciar skills">
        <CloseButton type="button" onClick={onClose} title="Fechar" aria-label="Fechar">
          <X size={18} weight="bold" />
        </CloseButton>
        <h2>Skills</h2>
        <p>
          Procedimentos prontos que o Avento segue à risca. Use com <code>/nome</code> no chat, ou deixe ativar
          sozinha quando sua mensagem bater com um gatilho.
        </p>
        <ScrollArea>
          <SkillDirectory>
            <SkillDirectoryHeader>
              <h3>Skills instaladas</h3>
              <span>{filteredSkills.length} de {skills.length}</span>
            </SkillDirectoryHeader>
            <SkillSearch>
              <MagnifyingGlass size={18} aria-hidden="true" />
              <input
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Buscar por nome, descrição ou gatilho"
                aria-label="Buscar skills instaladas"
                autoFocus
              />
              {search && (
                <button
                  type="button"
                  onClick={() => setSearch('')}
                  title="Limpar busca"
                  aria-label="Limpar busca"
                >
                  <X size={15} weight="bold" />
                </button>
              )}
            </SkillSearch>
            <SkillListViewport>
              <SkillList>
                {filteredSkills.map(skill => (
                  <SkillRow key={skill.name}>
                    <strong>/{skill.name}</strong>
                    <p>
                      {skill.description}
                      {skill.triggers.length > 0 && ` · gatilhos: ${skill.triggers.join(', ')}`}
                    </p>
                    <SkillBadge>{skill.builtin ? 'sistema' : 'sua'}</SkillBadge>
                    {!skill.builtin && (
                      <DeleteSkillButton
                        type="button"
                        title={`Remover /${skill.name}`}
                        aria-label={`Remover /${skill.name}`}
                        onClick={async () => {
                          const failure = await onDelete(skill.name);
                          if (failure) setError(failure);
                        }}
                      >
                        <Trash size={15} />
                      </DeleteSkillButton>
                    )}
                  </SkillRow>
                ))}
                {skills.length === 0 && <p>Nenhuma skill instalada ainda.</p>}
                {skills.length > 0 && filteredSkills.length === 0 && (
                  <p>Nenhuma skill encontrada para “{search.trim()}”.</p>
                )}
              </SkillList>
            </SkillListViewport>
          </SkillDirectory>
          <FormSection onSubmit={handleSubmit}>
            <h3>Criar nova skill</h3>
            <label>
              Nome (vira o comando /nome — só minúsculas, números e hífen)
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="deploy-front"
                required
              />
            </label>
            <label>
              Descrição curta
              <input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Faz o build e valida o frontend"
                required
              />
            </label>
            <label>
              Gatilhos de ativação automática (separados por vírgula — opcional)
              <input
                value={triggersText}
                onChange={(e) => setTriggersText(e.target.value)}
                placeholder="deploy do front, buildar frontend"
              />
            </label>
            <label>
              Procedimento (as instruções exatas que o Avento deve seguir)
              <textarea
                value={body}
                onChange={(e) => setBody(e.target.value)}
                placeholder={'Use terminal_run para executar npm run validate dentro de avento-web.\nSe falhar, mostre o erro real.'}
                required
              />
            </label>
            {error && <FormError>{error}</FormError>}
            <FormActions>
              <SaveButton type="submit" disabled={isSaving}>
                {isSaving ? 'Salvando…' : 'Salvar skill'}
              </SaveButton>
            </FormActions>
          </FormSection>
        </ScrollArea>
      </Modal>
    </Backdrop>
  );
}
