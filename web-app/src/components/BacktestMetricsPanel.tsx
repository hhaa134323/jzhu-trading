import { useI18n } from '../i18n';
import type { BacktestMetrics } from '../types';
import { formatNumber, formatPercent } from './backtestUtils.ts';

interface BacktestMetricsPanelProps {
  metrics?: BacktestMetrics;
  totalTrades?: number;
  className?: string;
}

function resolveReason(t: (key: string) => string, reason?: string | null) {
  if (!reason) {
    return '--';
  }
  const key = `backtest.metricReason.${reason}`;
  const translated = t(key);
  return translated === key ? reason : translated;
}

export default function BacktestMetricsPanel({ metrics, totalTrades, className }: BacktestMetricsPanelProps) {
  const { t } = useI18n();

  if (!metrics && totalTrades == null) {
    return null;
  }

  const reasonText = resolveReason(t, metrics?.reason);
  const renderPercent = (value?: number | null) => {
    if (value == null || Number.isNaN(value)) {
      return reasonText;
    }
    return formatPercent(value);
  };
  const renderNumber = (value?: number | null) => {
    if (value == null || Number.isNaN(value)) {
      return reasonText;
    }
    return formatNumber(value);
  };
  const renderCount = (value?: number | null) => {
    if (value == null || Number.isNaN(value)) {
      return reasonText;
    }
    return String(value);
  };
  const calmar =
    metrics?.maxDrawdownPct && metrics.maxDrawdownPct !== 0 && metrics.annualReturnPct != null
      ? metrics.annualReturnPct / Math.abs(metrics.maxDrawdownPct)
      : null;
  const renderCalmar = () => {
    if (calmar == null || Number.isNaN(calmar)) {
      return reasonText;
    }
    return calmar.toFixed(2);
  };
  const gridClassName = ['strategy-metric-grid', className].filter(Boolean).join(' ');

  return (
    <>
      {metrics ? (
        <div className={gridClassName}>
          {/* Row 1: 核心收益 */}
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricTotalReturn')}</div>
            <div className="strategy-metric-value">{renderPercent(metrics.totalReturnPct)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricFinalEquity')}</div>
            <div className="strategy-metric-value">{renderNumber(metrics.finalEquity)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricTotalPnl')}</div>
            <div className="strategy-metric-value">{renderNumber(metrics.totalPnl)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricMaxDrawdown')}</div>
            <div className="strategy-metric-value">{renderPercent(metrics.maxDrawdownPct)}</div>
          </div>
          {/* Row 2: 风险调整 */}
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricSharpe')}</div>
            <div className="strategy-metric-value">{renderNumber(metrics.sharpeRatio)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label" title={t('backtest.metricCalmarTooltip')}>{t('backtest.metricCalmar')}</div>
            <div className="strategy-metric-value">{renderCalmar()}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricAnnualReturn')}</div>
            <div className="strategy-metric-value">{renderPercent(metrics.annualReturnPct)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricVolatility')}</div>
            <div className="strategy-metric-value">{renderPercent(metrics.volatilityPct)}</div>
          </div>
          {/* Row 3: 交易特征 */}
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricWinRate')}</div>
            <div className="strategy-metric-value">{renderPercent(metrics.winRatePct)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricProfitFactor')}</div>
            <div className="strategy-metric-value">{renderNumber(metrics.profitFactor)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricClosedTrades')}</div>
            <div className="strategy-metric-value">{renderCount(metrics.closedTrades)}</div>
          </div>
          <div className="strategy-metric-card">
            <div className="strategy-metric-label">{t('backtest.metricAverageHoldDays')}</div>
            <div className="strategy-metric-value">{renderNumber(metrics.averageHoldDays)}</div>
          </div>
        </div>
      ) : null}
    </>
  );
}
