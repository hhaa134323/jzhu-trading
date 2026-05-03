import axios from 'axios';
import type {
  BacktestRequest,
  CloneStrategyTemplateRequest,
  CreateStrategyTemplateRequest,
  SaveStrategyTemplateVersionRequest,
  SimpleBacktestResponse,
  StrategyInfo,
  StrategyTemplateDetail,
  StrategyTemplateSummary,
  StrategyTemplateVersion,
} from '../types';

export const api = axios.create({
  baseURL: 'http://localhost:8181/api/web',
  timeout: 30000,
});

export async function fetchStrategies() {
  const response = await api.get<StrategyInfo[]>('/strategies');
  return response.data;
}

export async function runBacktest(request: BacktestRequest) {
  try {
    const response = await api.post<SimpleBacktestResponse>('/backtest/run', request);
    return response.data;
  } catch (err: any) {
    if (err?.response?.data && typeof err.response.data === 'object' && err.response.data.message) {
      throw new Error(err.response.data.message);
    }
    throw err;
  }
}

export async function fetchStrategyTemplates() {
  const response = await api.get<StrategyTemplateSummary[]>('/strategy-templates');
  return response.data;
}

export async function fetchStrategyTemplate(templateId: string) {
  const response = await api.get<StrategyTemplateDetail>(`/strategy-templates/${templateId}`);
  return response.data;
}

export async function fetchStrategyTemplateVersions(templateId: string) {
  const response = await api.get<StrategyTemplateVersion[]>(`/strategy-templates/${templateId}/versions`);
  return response.data;
}

export async function createStrategyTemplate(request: CreateStrategyTemplateRequest) {
  const response = await api.post<StrategyTemplateDetail>('/strategy-templates', request);
  return response.data;
}

export async function saveStrategyTemplateVersion(templateId: string, request: SaveStrategyTemplateVersionRequest) {
  const response = await api.post<StrategyTemplateDetail>(`/strategy-templates/${templateId}/versions`, request);
  return response.data;
}

export async function cloneStrategyTemplate(templateId: string, request: CloneStrategyTemplateRequest) {
  const response = await api.post<StrategyTemplateDetail>(`/strategy-templates/${templateId}/clone`, request);
  return response.data;
}
