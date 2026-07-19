import { useState, useEffect } from 'react';
import ReactDiffViewer from 'react-diff-viewer-continued';
import { useFileSystem } from '../../../hooks/useFileSystem';
import { Container, Header, Title, Actions, ApplyButton, DiffContainer, LoadingMessage, ErrorMessage, AppliedStatus } from './styles';
import { CheckCircle, Code } from '@phosphor-icons/react';

interface FileDiffViewerProps {
  filePath: string;
  newContent: string;
}

export function FileDiffViewer({ filePath, newContent }: FileDiffViewerProps) {
  const { readFile, writeFile, restoreFile } = useFileSystem();
  const [oldContent, setOldContent] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isApplied, setIsApplied] = useState(false);
  const [backupId, setBackupId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    async function loadOldContent() {
      setIsLoading(true);
      setError(null);
      try {
        const content = await readFile(filePath);
        setOldContent(content);
      } catch (err) {
        // Se falhar a leitura, assume que o arquivo não existe e está sendo criado.
        setOldContent('');
      } finally {
        setIsLoading(false);
      }
    }
    
    if (filePath) {
      loadOldContent();
    }
  }, [filePath, readFile]);

  const handleApply = async () => {
    setIsLoading(true);
    const result = await writeFile(filePath, newContent);
    setIsLoading(false);
    if (result.success) {
      setBackupId(result.backupId || null);
      setIsApplied(true);
    } else {
      setError('Falha ao salvar as alterações no arquivo.');
    }
  };

  const handleRestore = async () => {
    if (!backupId) return;

    setIsLoading(true);
    const success = await restoreFile(backupId);
    setIsLoading(false);

    if (success) {
      setIsApplied(false);
      setBackupId(null);
      setOldContent(await readFile(filePath));
    } else {
      setError('Falha ao reverter as alterações no arquivo.');
    }
  };

  const fileName = filePath.split('/').pop() || filePath;

  return (
    <Container>
      <Header>
        <Title>
          <Code size={18} />
          {fileName}
        </Title>
        <Actions>
          {isApplied ? (
            <>
              <AppliedStatus>
                <CheckCircle size={18} weight="fill" />
                Aplicado
              </AppliedStatus>
              {backupId && (
                <ApplyButton onClick={handleRestore} disabled={isLoading}>
                  Reverter
                </ApplyButton>
              )}
            </>
          ) : (
            <ApplyButton onClick={handleApply} disabled={isLoading || oldContent === null}>
              {isLoading ? 'Carregando...' : 'Aplicar Alterações'}
            </ApplyButton>
          )}
        </Actions>
      </Header>

      <DiffContainer>
        {isLoading && oldContent === null ? (
          <LoadingMessage>Carregando diff...</LoadingMessage>
        ) : error ? (
          <ErrorMessage>{error}</ErrorMessage>
        ) : (
          <ReactDiffViewer
            oldValue={oldContent || ''}
            newValue={newContent}
            splitView={true}
            useDarkTheme={true}
            leftTitle="Original"
            rightTitle="Sugerido"
            styles={{
              variables: {
                dark: {
                  diffViewerBackground: '#1e1e2e',
                  diffViewerColor: '#cdd6f4',
                  addedBackground: '#042f2e',
                  addedColor: '#34d399',
                  removedBackground: '#4c1d95',
                  removedColor: '#f472b6',
                  wordAddedBackground: '#064e3b',
                  wordRemovedBackground: '#5b21b6',
                  addedGutterBackground: '#022c22',
                  removedGutterBackground: '#4c1d95',
                  gutterBackground: '#181825',
                  gutterBackgroundDark: '#11111b',
                  highlightBackground: '#313244',
                  highlightGutterBackground: '#313244',
                  codeFoldGutterBackground: '#181825',
                  codeFoldBackground: '#181825',
                  emptyLineBackground: '#181825',
                  gutterColor: '#a6adc8',
                  addedGutterColor: '#6ee7b7',
                  removedGutterColor: '#f9a8d4',
                  codeFoldContentColor: '#cdd6f4'
                }
              }
            }}
          />
        )}
      </DiffContainer>
    </Container>
  );
}
