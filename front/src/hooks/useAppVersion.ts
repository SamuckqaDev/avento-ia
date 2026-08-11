import { useEffect, useState } from 'react';
import { api } from '../services/apiClient';

interface AppVersionResponse {
  version?: string;
}

export function useAppVersion(): string | null {
  const [version, setVersion] = useState<string | null>(null);

  useEffect(() => {
    let active = true;

    api.get<AppVersionResponse>('/api/version')
      .then(({ data }) => {
        if (active) {
          setVersion(data.version || null);
        }
      })
      .catch(() => {
        if (active) {
          setVersion(null);
        }
      });

    return () => {
      active = false;
    };
  }, []);

  return version;
}
