export type MarketCode = 'us' | 'hk' | 'cn';
export type PeriodCode = 'daily' | 'weekly' | 'monthly';
export type WorkbenchModel = 'ChatGPT (gpt-5.4)' | 'Claude 3.7 Sonnet' | 'DeepSeek-R1';

export interface Kline {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface MacdResult {
  difList: Array<number | null>;
  deaList: Array<number | null>;
  macdList: Array<number | null>;
}

export interface MaResult {
  ma5List: Array<number | null>;
  ma10List: Array<number | null>;
  ma20List: Array<number | null>;
  ma30List: Array<number | null>;
  ma60List: Array<number | null>;
}

export interface RsiResult {
  rsi6List: Array<number | null>;
  rsi12List: Array<number | null>;
  rsi24List: Array<number | null>;
}

export interface BollResult {
  upperList: Array<number | null>;
  middleList: Array<number | null>;
  lowerList: Array<number | null>;
}

export interface IndicatorResponse {
  macd: MacdResult;
  ma: MaResult;
  rsi: RsiResult;
  boll: BollResult;
}

export interface KlineWithIndicatorsResponse {
  klines: Kline[];
  indicators: IndicatorResponse;
  totalCount: number;
  earliestAvailableDate?: string;
}

export interface SearchFormValues {
  symbol: string;
  market: MarketCode;
  period: PeriodCode;
  startDate: string;
  endDate: string;
}

export interface StockTab extends SearchFormValues {
  id: string;
  label: string;
  klines: Kline[];
  indicators: IndicatorResponse;
  totalCount: number;
}

export interface BacktestRequest {
  symbol: string;
  market: MarketCode;
  period: PeriodCode;
  startDate: string;
  endDate: string;
  strategyId?: string;
  strategySource?: StrategySource;
  runParameters?: RunParameters;
}

export interface RunParameters {
  capital?: number;
  leverage?: number;
  feeRate?: number;
  slippageBps?: number;
  commissionBps?: number;
}

export type StrategySourceType = 'BUILTIN' | 'TEMPLATE_VERSION' | 'DRAFT';

export interface StrategyParameters {
  breakoutLookbackBars?: number;
  pullbackMaPeriod?: number;
  macdFast?: number;
  macdSlow?: number;
  macdSignal?: number;
  closeMaFast?: number;
  closeMaSlow?: number;
}

export interface StrategyDefinition {
  engineType: string;
  baseStrategyId: string;
  parameters?: StrategyParameters;
}

export interface StrategySource {
  sourceType: StrategySourceType;
  builtinStrategyId?: string;
  templateId?: string;
  templateVersion?: number;
  draftDefinition?: StrategyDefinition;
}

export interface BacktestTradeDetail {
  openIndex: number;
  closeIndex: number;
  openDate: string;
  closeDate: string | null;
  openPrice: number;
  closePrice: number;
  direction: 'LONG' | 'SHORT';
  openReason: string;
  closeReason: string | null;
  closed: boolean;
  fee?: number | null;
}

export interface SimpleBacktestResponse {
  symbol: string;
  strategyId: string;
  strategyName: string;
  totalTrades: number;
  trades: BacktestTradeDetail[];
  metrics?: BacktestMetrics;
}

export interface BacktestMetrics {
  totalReturnPct?: number | null;
  maxDrawdownPct?: number | null;
  sharpeRatio?: number | null;
  annualReturnPct?: number | null;
  volatilityPct?: number | null;
  winRatePct?: number | null;
  profitFactor?: number | null;
  closedTrades?: number | null;
  averageHoldBars?: number | null;
  averageHoldDays?: number | null;
  finalEquity?: number | null;
  totalPnl?: number | null;
  reason?: string | null;
}

export interface StrategyDraft {
  localId: string;
  templateId?: string;
  latestVersion?: number;
  name: string;
  description: string;
  ownerId: string;
  model: WorkbenchModel;
  buyStrategy: string;
  sellStrategy: string;
  changeNote: string;
  codeText: string;
}

export interface StrategyInfo {
  id: string;
  name: string;
  description: string;
}

export interface StrategyTemplateVersion {
  templateId: string;
  versionNo: number;
  sourceKind: string;
  definition: StrategyDefinition;
  changeNote?: string;
  createdBy?: string;
  createdAt: string;
}

export interface StrategyTemplateSummary {
  templateId: string;
  name: string;
  description?: string;
  ownerId: string;
  status: string;
  latestVersion?: number;
  updatedAt: string;
}

export interface StrategyTemplateDetail {
  templateId: string;
  name: string;
  description?: string;
  ownerId: string;
  status: string;
  latestVersion?: number;
  createdAt: string;
  updatedAt: string;
  versions: StrategyTemplateVersion[];
}

export interface CreateStrategyTemplateRequest {
  name: string;
  description?: string;
  ownerId?: string;
  initialDefinition: StrategyDefinition;
  changeNote?: string;
}

export interface SaveStrategyTemplateVersionRequest {
  definition: StrategyDefinition;
  changeNote?: string;
  createdBy?: string;
}

export interface CloneStrategyTemplateRequest {
  name: string;
  description?: string;
  ownerId?: string;
  fromVersion?: number;
  changeNote?: string;
}
