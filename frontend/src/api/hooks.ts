import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type {
  AuditEntry, Environment, Experiment, GuardedRollout, MetricType, EnvironmentWithSdkKey, EvaluationResponse, Flag, FlagConfig, FlagSummary, FlagType,
  Page, Project, ProjectCreated, Role, Segment, SegmentRule, Targeting, User, Variation,
} from './types';

const enc = encodeURIComponent;

export const keys = {
  projects: ['projects'] as const,
  project: (p: string) => ['projects', p] as const,
  flags: (p: string) => ['projects', p, 'flags'] as const,
  flag: (p: string, f: string) => ['projects', p, 'flags', f] as const,
  segments: (p: string) => ['projects', p, 'segments'] as const,
  audit: (p: string) => ['projects', p, 'audit'] as const,
  users: ['users'] as const,
  experiments: (p: string) => ['projects', p, 'experiments'] as const,
  rollouts: (p: string) => ['projects', p, 'rollouts'] as const,
};

// ---------------------------------------------------------------- projects & environments

export const useProjects = () => useQuery({ queryKey: keys.projects, queryFn: () => api<Project[]>('/projects') });

export const useProject = (projectKey: string) =>
  useQuery({ queryKey: keys.project(projectKey), queryFn: () => api<Project>(`/projects/${enc(projectKey)}`) });

export function useCreateProject() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { key: string; name: string; description?: string }) =>
      api<ProjectCreated>('/projects', { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.projects }),
  });
}

export function useCreateEnvironment(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { key: string; name: string; color: string }) =>
      api<EnvironmentWithSdkKey>(`/projects/${enc(projectKey)}/environments`, { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.project(projectKey) }),
  });
}

export function useRotateSdkKey(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (envKey: string) =>
      api<EnvironmentWithSdkKey>(`/projects/${enc(projectKey)}/environments/${enc(envKey)}/sdk-key/rotate`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.project(projectKey) }),
  });
}

export function useDeleteEnvironment(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (envKey: string) => api<void>(`/projects/${enc(projectKey)}/environments/${enc(envKey)}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.project(projectKey) }),
  });
}

// ---------------------------------------------------------------- flags

export interface FlagFilter {
  q?: string;
  tag?: string;
  archived?: boolean;
  page?: number;
}

export const useFlags = (projectKey: string, filter: FlagFilter) =>
  useQuery({
    queryKey: [...keys.flags(projectKey), filter],
    queryFn: () => {
      const params = new URLSearchParams({ size: '50', page: String(filter.page ?? 0), archived: String(!!filter.archived) });
      if (filter.q) params.set('q', filter.q);
      if (filter.tag) params.set('tag', filter.tag);
      return api<Page<FlagSummary>>(`/projects/${enc(projectKey)}/flags?${params}`);
    },
    placeholderData: keepPreviousData,
  });

export const useTags = (projectKey: string) =>
  useQuery({ queryKey: [...keys.flags(projectKey), 'tags'], queryFn: () => api<string[]>(`/projects/${enc(projectKey)}/flags/tags`) });

export const useFlag = (projectKey: string, flagKey: string) =>
  useQuery({ queryKey: keys.flag(projectKey, flagKey), queryFn: () => api<Flag>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}`) });

export interface CreateFlagBody {
  key: string;
  name: string;
  description?: string;
  type: FlagType;
  variations?: Variation[];
  defaultOnVariation?: number;
  defaultOffVariation?: number;
  tags?: string[];
  temporary?: boolean;
}

export function useCreateFlag(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateFlagBody) => api<Flag>(`/projects/${enc(projectKey)}/flags`, { method: 'POST', json: body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.flags(projectKey) });
      qc.invalidateQueries({ queryKey: keys.project(projectKey) });
    },
  });
}

/** Invalidates everything derived from a flag after a write. */
function useFlagInvalidation(projectKey: string, flagKey: string) {
  const qc = useQueryClient();
  return () => {
    qc.invalidateQueries({ queryKey: keys.flags(projectKey) });
    qc.invalidateQueries({ queryKey: keys.flag(projectKey, flagKey) });
    qc.invalidateQueries({ queryKey: keys.audit(projectKey) });
  };
}

export function useUpdateFlag(projectKey: string, flagKey: string) {
  const invalidate = useFlagInvalidation(projectKey, flagKey);
  return useMutation({
    mutationFn: (body: Partial<Pick<Flag, 'name' | 'description' | 'tags' | 'temporary' | 'variations'>>) =>
      api<Flag>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}`, { method: 'PATCH', json: body }),
    onSuccess: invalidate,
  });
}

export function useFlagAction(projectKey: string, flagKey: string) {
  const invalidate = useFlagInvalidation(projectKey, flagKey);
  return useMutation({
    mutationFn: (action: 'archive' | 'restore' | 'delete'): Promise<Flag | void> =>
      action === 'delete'
        ? api<void>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}`, { method: 'DELETE' })
        : api<Flag>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}/${action}`, { method: 'POST' }),
    onSuccess: invalidate,
  });
}

export function useToggleFlag(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { flagKey: string; envKey: string; enabled: boolean; comment?: string }) =>
      api<FlagConfig>(`/projects/${enc(projectKey)}/flags/${enc(v.flagKey)}/environments/${enc(v.envKey)}/toggle`,
        { method: 'POST', json: { enabled: v.enabled, comment: v.comment } }),
    onSuccess: (_, v) => {
      qc.invalidateQueries({ queryKey: keys.flags(projectKey) });
      qc.invalidateQueries({ queryKey: keys.flag(projectKey, v.flagKey) });
      qc.invalidateQueries({ queryKey: keys.audit(projectKey) });
    },
  });
}

export function useUpdateTargeting(projectKey: string, flagKey: string) {
  const invalidate = useFlagInvalidation(projectKey, flagKey);
  return useMutation({
    mutationFn: (v: { envKey: string; targeting: Targeting; enabled: boolean; version: number; comment?: string }) =>
      api<FlagConfig>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}/environments/${enc(v.envKey)}`, {
        method: 'PUT',
        json: { targeting: v.targeting, enabled: v.enabled, version: v.version, comment: v.comment },
      }),
    onSuccess: invalidate,
  });
}

export function useCopyTargeting(projectKey: string, flagKey: string) {
  const invalidate = useFlagInvalidation(projectKey, flagKey);
  return useMutation({
    mutationFn: (v: { targetEnv: string; sourceEnvironment: string; includeEnabledState: boolean; targetVersion: number }) =>
      api<FlagConfig>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}/environments/${enc(v.targetEnv)}/copy`, {
        method: 'POST',
        json: { sourceEnvironment: v.sourceEnvironment, includeEnabledState: v.includeEnabledState, targetVersion: v.targetVersion },
      }),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------- segments

export const useSegments = (projectKey: string) =>
  useQuery({ queryKey: keys.segments(projectKey), queryFn: () => api<Segment[]>(`/projects/${enc(projectKey)}/segments`) });

export interface SegmentBody {
  key?: string;
  name: string;
  description?: string;
  included: string[];
  excluded: string[];
  rules: SegmentRule[];
  version?: number;
}

export function useSaveSegment(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ isNew, body }: { isNew: boolean; body: SegmentBody }) =>
      isNew
        ? api<Segment>(`/projects/${enc(projectKey)}/segments`, { method: 'POST', json: body })
        : api<Segment>(`/projects/${enc(projectKey)}/segments/${enc(body.key!)}`, { method: 'PUT', json: body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.segments(projectKey) });
      qc.invalidateQueries({ queryKey: keys.audit(projectKey) });
    },
  });
}

export function useDeleteSegment(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (segmentKey: string) => api<void>(`/projects/${enc(projectKey)}/segments/${enc(segmentKey)}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.segments(projectKey) }),
  });
}

// ---------------------------------------------------------------- audit, debugger, users

export const useAudit = (projectKey: string, filter: { page: number; resourceKey?: string; environment?: string }) =>
  useQuery({
    queryKey: [...keys.audit(projectKey), filter],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(filter.page), size: '25' });
      if (filter.resourceKey) params.set('resourceKey', filter.resourceKey);
      if (filter.environment) params.set('environment', filter.environment);
      return api<Page<AuditEntry>>(`/projects/${enc(projectKey)}/audit?${params}`);
    },
    placeholderData: keepPreviousData,
  });

export function useEvaluate(projectKey: string) {
  return useMutation({
    mutationFn: (v: { envKey: string; context: { key: string; attributes?: Record<string, unknown> } }) =>
      api<EvaluationResponse>(`/projects/${enc(projectKey)}/environments/${enc(v.envKey)}/evaluate`, {
        method: 'POST',
        json: { context: v.context },
      }),
  });
}

export const useUsers = () => useQuery({ queryKey: keys.users, queryFn: () => api<User[]>('/users') });

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { email: string; displayName: string; password: string; role: Role }) =>
      api<User>('/users', { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

export function useUpdateUserRole() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { id: number; role: Role }) => api<User>(`/users/${v.id}/role`, { method: 'PUT', json: { role: v.role } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.users }),
  });
}

// ---------------------------------------------------------------- experiments

export const useExperiments = (projectKey: string) =>
  useQuery({ queryKey: keys.experiments(projectKey), queryFn: () => api<Experiment[]>(`/projects/${enc(projectKey)}/experiments`) });

/** Results are computed on read, so poll while the page is open to watch a running experiment. */
export const useExperiment = (projectKey: string, key: string) =>
  useQuery({
    queryKey: [...keys.experiments(projectKey), key],
    queryFn: () => api<Experiment>(`/projects/${enc(projectKey)}/experiments/${enc(key)}`),
    refetchInterval: (q) => (q.state.data?.status === 'RUNNING' ? 30_000 : false),
  });

export interface CreateExperimentBody {
  key: string;
  name: string;
  hypothesis?: string;
  environment: string;
  flagKey: string;
  metricEventKey: string;
  metricType: MetricType;
  higherIsBetter: boolean;
  controlVariation: number;
}

export function useCreateExperiment(projectKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateExperimentBody) => api<Experiment>(`/projects/${enc(projectKey)}/experiments`, { method: 'POST', json: body }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.experiments(projectKey) }),
  });
}

export function useStopExperiment(projectKey: string, key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api<Experiment>(`/projects/${enc(projectKey)}/experiments/${enc(key)}/stop`, { method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: keys.experiments(projectKey) }),
  });
}

// ---------------------------------------------------------------- guarded rollouts

const isActive = (r?: GuardedRollout) => r?.status === 'RUNNING' || r?.status === 'PAUSED';

export const useRollouts = (projectKey: string) =>
  useQuery({
    queryKey: keys.rollouts(projectKey),
    queryFn: () => api<GuardedRollout[]>(`/projects/${enc(projectKey)}/rollouts`),
    refetchInterval: (q) => (q.state.data?.some(isActive) ? 5_000 : false),
  });

/** Polls while the rollout is active so the progress bar and guardrail update live. */
export const useRollout = (projectKey: string, id: number) =>
  useQuery({
    queryKey: [...keys.rollouts(projectKey), id],
    queryFn: () => api<GuardedRollout>(`/projects/${enc(projectKey)}/rollouts/${id}`),
    refetchInterval: (q) => (isActive(q.state.data) ? 3_000 : false),
  });

export interface StartRolloutBody {
  treatmentVariation: number;
  controlVariation: number;
  steps: number[];
  stepIntervalSeconds: number;
  minUsersPerStep: number;
  guardrailEventKey?: string;
}

export function useStartRollout(projectKey: string, flagKey: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { envKey: string; body: StartRolloutBody }) =>
      api<GuardedRollout>(`/projects/${enc(projectKey)}/flags/${enc(flagKey)}/environments/${enc(v.envKey)}/rollouts`, { method: 'POST', json: v.body }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.rollouts(projectKey) });
      qc.invalidateQueries({ queryKey: keys.flag(projectKey, flagKey) });
      qc.invalidateQueries({ queryKey: keys.flags(projectKey) });
    },
  });
}

export function useRolloutAction(projectKey: string, id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (action: 'pause' | 'resume' | 'cancel') => api<GuardedRollout>(`/projects/${enc(projectKey)}/rollouts/${id}/${action}`, { method: 'POST' }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: keys.rollouts(projectKey) });
      qc.invalidateQueries({ queryKey: keys.flags(projectKey) });
    },
  });
}

export type { Environment };
