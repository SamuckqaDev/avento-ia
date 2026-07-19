import { useState } from 'react';
import { DownloadSimple, FilePdf } from '@phosphor-icons/react';
import { Card, Header, DocumentInfo, DownloadButton, ErrorText } from './styles';
import { apiErrorMessage } from '../../../services/apiClient';

export function DocumentCard({ filename }: { filename: string }) {
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState('');

  const download = async () => {
    setDownloading(true);
    setError('');
    try {
      const response = await fetch(`/api/media/${filename}`);
      if (!response.ok) {
        throw new Error('Falha ao baixar documento');
      }
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      setError(err instanceof Error ? err.message : apiErrorMessage(err));
    } finally {
      setDownloading(false);
    }
  };

  return (
    <Card>
      <Header>
        <DocumentInfo>
          <FilePdf size={24} weight="duotone" />
          <span>{filename}</span>
        </DocumentInfo>
        <DownloadButton type="button" onClick={download} disabled={downloading} title="Baixar documento">
          <DownloadSimple size={18} />
        </DownloadButton>
      </Header>
      {error && <ErrorText>{error}</ErrorText>}
    </Card>
  );
}
