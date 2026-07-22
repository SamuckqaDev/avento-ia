import { api } from './apiClient';

export interface PlanCreateRequest {
  goal: string;
  workspaceRoots: string[];
  chatId: number;
}

export interface TaskResponse {
  id: number;
  orderIndex: number;
  title: string;
  details: string;
  status: string;
  needsApproval: boolean;
  resultSummary?: string;
  targetFiles?: string;
  assignedAgentId?: number;
  assignedAgentName?: string;
  agentRationale?: string;
}

export interface PlanResponse {
  id: number;
  chatId: number;
  goal: string;
  status: string;
  currentTaskId?: number;
  currentRunId?: string;
  tasks: TaskResponse[];
}

export interface TaskUpdateRequest {
  title: string;
  details: string;
  needsApproval: boolean;
  orderIndex?: number;
  skipped?: boolean;
}

export const planApi = {
  async createPlan(request: PlanCreateRequest): Promise<PlanResponse> {
    const response = await api.post<PlanResponse>('/api/plans', request);
    return response.data;
  },

  async getPlan(id: number): Promise<PlanResponse> {
    const response = await api.get<PlanResponse>(`/api/plans/${id}`);
    return response.data;
  },

  async listPlans(): Promise<PlanResponse[]> {
    const response = await api.get<PlanResponse[]>('/api/plans');
    return response.data;
  },

  async updateTask(planId: number, taskId: number, update: TaskUpdateRequest): Promise<TaskResponse> {
    const response = await api.put<TaskResponse>(`/api/plans/${planId}/tasks/${taskId}`, update);
    return response.data;
  },

  async approveTask(planId: number, taskId: number): Promise<TaskResponse> {
    const response = await api.post<TaskResponse>(`/api/plans/${planId}/tasks/${taskId}/approve`);
    return response.data;
  },

  async runPlan(planId: number): Promise<PlanResponse> {
    const response = await api.post<PlanResponse>(`/api/plans/${planId}/run`);
    return response.data;
  },

  async pausePlan(planId: number): Promise<PlanResponse> {
    const response = await api.post<PlanResponse>(`/api/plans/${planId}/pause`);
    return response.data;
  },

  async resumePlan(planId: number): Promise<PlanResponse> {
    const response = await api.post<PlanResponse>(`/api/plans/${planId}/resume`);
    return response.data;
  },

  async cancelPlan(planId: number): Promise<PlanResponse> {
    const response = await api.post<PlanResponse>(`/api/plans/${planId}/cancel`);
    return response.data;
  },
};
