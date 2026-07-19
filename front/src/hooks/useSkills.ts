import { useCallback, useEffect, useState } from 'react';
import { api } from '../services/apiClient';

export interface Skill {
  name: string;
  description: string;
  triggers: string[];
  body: string;
  builtin: boolean;
}

export interface CreateSkillInput {
  name: string;
  description: string;
  triggers: string[];
  body: string;
}

const API_SKILLS = '/api/skills';

export function useSkills() {
  const [skills, setSkills] = useState<Skill[]>([]);

  const loadSkills = useCallback(async () => {
    try {
      const { data } = await api.get<Skill[]>(API_SKILLS);
      setSkills(data);
    } catch (e) {
      console.error('Erro ao carregar skills', e);
    }
  }, []);

  const createSkill = useCallback(async (input: CreateSkillInput): Promise<string | null> => {
    try {
      await api.post(API_SKILLS, input);
      await loadSkills();
      return null;
    } catch (e: unknown) {
      const axiosError = e as { response?: { data?: { error?: string } } };
      return axiosError.response?.data?.error || 'Não foi possível salvar a skill.';
    }
  }, [loadSkills]);

  const deleteSkill = useCallback(async (name: string): Promise<string | null> => {
    try {
      await api.delete(`${API_SKILLS}/${encodeURIComponent(name)}`);
      await loadSkills();
      return null;
    } catch (e: unknown) {
      const axiosError = e as { response?: { data?: { error?: string } } };
      return axiosError.response?.data?.error || 'Não foi possível remover a skill.';
    }
  }, [loadSkills]);

  useEffect(() => {
    loadSkills();
  }, [loadSkills]);

  return { skills, loadSkills, createSkill, deleteSkill };
}
