import type {
  BacktestRequest,
  RunParameters,
  SearchFormValues,
  StrategyDefinition,
  StrategyDraft,
  StrategySource,
} from '../types';

function sanitizeParameters(input: Record<string, unknown> | undefined) {
  if (!input) {
    return undefined;
  }

  const entries = Object.entries(input).filter(([, value]) => typeof value === 'number' && Number.isFinite(value));
  if (entries.length === 0) {
    return undefined;
  }

  return Object.fromEntries(entries);
}

export function parseStrategyDefinition(codeText: string): StrategyDefinition {
  const parsed = JSON.parse(codeText) as {
    engineType?: unknown;
    baseStrategyId?: unknown;
    parameters?: Record<string, unknown>;
  };

  const engineType = typeof parsed.engineType === 'string' ? parsed.engineType.trim() : '';
  const baseStrategyId = typeof parsed.baseStrategyId === 'string' ? parsed.baseStrategyId.trim() : '';

  if (!engineType) {
    throw new Error('策略代码缺少 engineType');
  }
  if (!baseStrategyId) {
    throw new Error('策略代码缺少 baseStrategyId');
  }

  return {
    engineType,
    baseStrategyId,
    parameters: sanitizeParameters(parsed.parameters),
  };
}

export function buildStrategySource(strategy: StrategyDraft): { strategySource: StrategySource; strategyId: string } {
  // PYTHON_CODE path: codeText is Python source, not JSON — skip JSON.parse entirely
  if (strategy.sourceKind === 'PYTHON_CODE') {
    if (!strategy.templateId) {
      throw new Error('PYTHON_CODE 暂时只支持 TEMPLATE_VERSION 路径');
    }
    return {
      strategyId: strategy.templateId,
      strategySource: {
        sourceType: 'TEMPLATE_VERSION',
        templateId: strategy.templateId,
        templateVersion: strategy.latestVersion ?? 1,
      },
    };
  }

  // JAVA_PARAMS / default path: codeText is JSON, parse normally
  const definition = parseStrategyDefinition(strategy.codeText);

  if (strategy.templateId) {
    return {
      strategyId: definition.baseStrategyId,
      strategySource: {
        sourceType: 'TEMPLATE_VERSION',
        templateId: strategy.templateId,
        templateVersion: strategy.latestVersion ?? 1,
      },
    };
  }

  return {
    strategyId: definition.baseStrategyId,
    strategySource: {
      sourceType: 'DRAFT',
      draftDefinition: definition,
    },
  };
}

export function buildBacktestRequest(
  strategy: StrategyDraft,
  form: SearchFormValues,
  runParameters?: RunParameters,
): BacktestRequest {
  const sourcePayload = buildStrategySource(strategy);

  return {
    symbol: form.symbol.trim().toUpperCase(),
    market: form.market,
    period: form.period,
    startDate: form.startDate,
    endDate: form.endDate,
    strategyId: sourcePayload.strategyId,
    strategySource: sourcePayload.strategySource,
    runParameters,
  };
}

export function formatPercent(value?: number | null) {
  if (value == null || Number.isNaN(value)) {
    return '--';
  }
  return `${value.toFixed(2)}%`;
}

export function formatNumber(value?: number | null) {
  if (value == null || Number.isNaN(value)) {
    return '--';
  }
  return value.toFixed(2);
}
