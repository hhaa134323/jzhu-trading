import { useMemo, useState } from 'react';
import { runBacktest } from '../api';
import { useI18n } from '../i18n';
import type {
  BacktestMetrics,
  BacktestTradeDetail,
  SearchFormValues,
  SimpleBacktestResponse,
  StrategyDraft,
} from '../types';
import { buildBacktestRequest, formatNumber, formatPercent } from './backtestUtils.ts';

interface BacktestPanelProps {
  open: boolean;
  strategy: StrategyDraft | null;
  form: SearchFormValues;
  onClose: () => void;
  onApplied: (result: SimpleBacktestResponse) => void;
  onClear: () => void;
}

function computeMetrics(trades: BacktestTradeDetail[]): BacktestMetrics {
  const closedTrades = trades.filter((trade) => trade.closed && trade.closeDate && trade.openPrice > 0);
  if (closedTrades.length === 0) {
    return {
      closedTrades: 0,
      winRatePct: 0,
    };
  }

  const tradeReturns = closedTrades.map((trade) => ((trade.closePrice - trade.openPrice) / trade.openPrice) * 100);
  const wins = tradeReturns.filter((ret) => ret > 0);
  const losses = tradeReturns.filter((ret) => ret < 0);

  const grossProfit = wins.reduce((sum, value) => sum + value, 0);
  const grossLossAbs = Math.abs(losses.reduce((sum, value) => sum + value, 0));
  const totalHoldBars = closedTrades.reduce((sum, trade) => sum + Math.max(trade.closeIndex - trade.openIndex, 0), 0);
  const totalHoldDays = closedTrades.reduce((sum, trade) => {
    const openTs = new Date(trade.openDate).getTime();
    const closeTs = new Date(trade.closeDate as string).getTime();
    if (Number.isNaN(openTs) || Number.isNaN(closeTs) || closeTs < openTs) {
      return sum;
    }
    const diffDays = (closeTs - openTs) / (24 * 60 * 60 * 1000);
    return sum + diffDays;
  }, 0);

  return {
    closedTrades: closedTrades.length,
    winRatePct: (wins.length / closedTrades.length) * 100,
    profitFactor: grossLossAbs > 0 ? grossProfit / grossLossAbs : undefined,
    averageHoldBars: totalHoldBars / closedTrades.length,
    averageHoldDays: totalHoldDays / closedTrades.length,
  };
}

export default function BacktestPanel({ open, strategy, form, onClose, onApplied, onClear }: BacktestPanelProps) {
  const { t } = useI18n();
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<SimpleBacktestResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [capitalInput, setCapitalInput] = useState('100000');
  const [leverageInput, setLeverageInput] = useState('1');
  const [slippageBpsInput, setSlippageBpsInput] = useState('5');
  const [commissionBpsInput, setCommissionBpsInput] = useState('1');

  const strategyTitle = strategy
    ? strategy.templateId
      ? `${strategy.name} · ${strategy.templateId} · v${strategy.latestVersion ?? 1}`
      : `${strategy.name} · ${t('backtest.sourceDraft')}`
    : t('backtest.noStrategy');

  const metrics = useMemo(() => {
    if (!result) {
      return null;
    }
    const baseMetrics = computeMetrics(result.trades);
    return {
      ...baseMetrics,
      ...result.metrics,
    };
  }, [result]);

  const handleRunBacktest = async () => {
    if (!strategy) {
      setError(t('backtest.selectStrategyFirst'));
      return;
    }

    const capital = Number(capitalInput);
    if (!Number.isFinite(capital) || capital <= 0) {
      setError(t('backtest.paramInvalidCapital'));
      return;
    }
    const leverage = Number(leverageInput);
    if (!Number.isFinite(leverage) || leverage < 1) {
      setError(t('backtest.paramInvalidLeverage'));
      return;
    }
    const slippageBps = Number(slippageBpsInput);
    if (!Number.isFinite(slippageBps) || slippageBps < 0) {
      setError(t('backtest.paramInvalidSlippageBps'));
      return;
    }
    const commissionBps = Number(commissionBpsInput);
    if (!Number.isFinite(commissionBps) || commissionBps < 0) {
      setError(t('backtest.paramInvalidCommissionBps'));
      return;
    }

    setRunning(true);
    setError(null);

    try {
      // feeRate is derived from commissionBps for backward compat with backend
      const feeRate = commissionBps / 10000;
      const request = buildBacktestRequest(strategy, form, { capital, leverage, feeRate, slippageBps });
      const data = await runBacktest(request);
      setResult(data);
      onApplied(data);
    } catch (err) {
      const message = err instanceof Error ? err.message : t('backtest.runFailed');
      setError(message);
    } finally {
      setRunning(false);
    }
  };

  if (!open) {
    return null;
  }

  return (
    <div className="strategy-modal-backdrop" role="presentation" onClick={onClose}>
      <div
        className="strategy-modal panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="strategy-backtest-title"
        aria-label={t('backtest.modalLabel')}
        onClick={(event) => event.stopPropagation()}
      >
        <div className="d-flex align-items-start justify-content-between gap-3 mb-3">
          <div>
            <h3 id="strategy-backtest-title" className="fs-5 fw-semibold mb-1">{t('backtest.modalTitle')}</h3>
            <div className="text-muted-custom small">{t('backtest.modalHint')}</div>
          </div>
          <button type="button" className="btn btn-sm btn-outline-light" onClick={onClose}>
            {t('backtest.close')}
          </button>
        </div>

        <div className="row g-3 mb-3">
          <div className="col-12 col-lg-7">
            <div className="strategy-meta-block h-100">
              <div className="text-muted-custom small mb-1">{t('backtest.currentStrategy')}</div>
              <div className="fw-semibold text-break">{strategyTitle}</div>
            </div>
          </div>
          <div className="col-12 col-lg-5">
            <div className="strategy-meta-block h-100">
              <div className="text-muted-custom small mb-1">{t('backtest.currentQuery')}</div>
              <div className="small fw-semibold text-break">
                {form.symbol.toUpperCase()} · {t(`common.market.${form.market}`)} · {t(`common.period.${form.period}`)}
              </div>
              <div className="small text-muted-custom mt-1">
                {form.startDate} ~ {form.endDate}
              </div>
            </div>
          </div>
        </div>

        <div className="strategy-meta-block mb-3">
          <div className="text-muted-custom small mb-2">{t('backtest.runParameters')}</div>
          <div className="row g-2">
            <div className="col-6 col-md-3">
              <label className="form-label small text-muted-custom" htmlFor="backtest-capital">
                {t('backtest.capital')}
              </label>
              <input
                id="backtest-capital"
                type="number"
                min={0}
                step={1000}
                className="form-control form-control-sm"
                value={capitalInput}
                onChange={(event) => setCapitalInput(event.target.value)}
              />
            </div>
            <div className="col-6 col-md-2">
              <label className="form-label small text-muted-custom" htmlFor="backtest-leverage">
                {t('backtest.leverage')}
              </label>
              <input
                id="backtest-leverage"
                type="number"
                min={1}
                step={0.1}
                className="form-control form-control-sm"
                value={leverageInput}
                onChange={(event) => setLeverageInput(event.target.value)}
              />
            </div>
            <div className="col-6 col-md-2">
              <label className="form-label small text-muted-custom" htmlFor="backtest-commission-bps">
                {t('backtest.commissionBps')}
              </label>
              <input
                id="backtest-commission-bps"
                type="number"
                min={0}
                step={0.1}
                className="form-control form-control-sm"
                value={commissionBpsInput}
                onChange={(event) => setCommissionBpsInput(event.target.value)}
              />
            </div>
            <div className="col-6 col-md-2">
              <label className="form-label small text-muted-custom" htmlFor="backtest-slippage-bps">
                {t('backtest.slippageBps')}
              </label>
              <input
                id="backtest-slippage-bps"
                type="number"
                min={0}
                step={0.1}
                className="form-control form-control-sm"
                value={slippageBpsInput}
                onChange={(event) => setSlippageBpsInput(event.target.value)}
              />
            </div>

          </div>
          <div className="text-muted-custom small mt-2">{t('backtest.runParametersHint')}</div>
        </div>

        <div className="d-flex flex-wrap align-items-center gap-2 mb-3">
          <button
            type="button"
            className="btn btn-sm btn-brand-orange"
            disabled={running || !strategy}
            onClick={handleRunBacktest}
          >
            {running ? t('backtest.running') : t('backtest.run')}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-outline-light"
            disabled={running}
            onClick={() => {
              setResult(null);
              setError(null);
              onClear();
            }}
          >
            {t('backtest.clear')}
          </button>
        </div>

        {error ? <div className="alert alert-danger border-0 mb-3">{error}</div> : null}

        {metrics ? (
          <>
            <div className="strategy-metric-grid mb-3">
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricTotalReturn')}</div>
                <div className="strategy-metric-value">{metrics.totalReturnPct == null ? (result?.metrics?.reason ? t(`backtest.metricReason.${result.metrics.reason}`) || result.metrics.reason : '--') : formatPercent(metrics.totalReturnPct)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricFinalEquity')}</div>
                <div className="strategy-metric-value">{formatNumber(metrics.finalEquity)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricTotalPnl')}</div>
                <div className="strategy-metric-value">{formatNumber(metrics.totalPnl)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricMaxDrawdown')}</div>
                <div className="strategy-metric-value">{metrics.maxDrawdownPct == null ? (result?.metrics?.reason ? t(`backtest.metricReason.${result.metrics.reason}`) || result.metrics.reason : '--') : formatPercent(metrics.maxDrawdownPct)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricWinRate')}</div>
                <div className="strategy-metric-value">{formatPercent(metrics.winRatePct)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricProfitFactor')}</div>
                <div className="strategy-metric-value">{formatNumber(metrics.profitFactor)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricSharpe')}</div>
                <div className="strategy-metric-value">{metrics.sharpeRatio == null ? (result?.metrics?.reason ? t(`backtest.metricReason.${result.metrics.reason}`) || result.metrics.reason : '--') : formatNumber(metrics.sharpeRatio)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricClosedTrades')}</div>
                <div className="strategy-metric-value">{String(metrics.closedTrades ?? '--')}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricAverageHoldBars')}</div>
                <div className="strategy-metric-value">{formatNumber(metrics.averageHoldBars)}</div>
              </div>
              <div className="strategy-metric-card">
                <div className="strategy-metric-label">{t('backtest.metricAverageHoldDays')}</div>
                <div className="strategy-metric-value">{formatNumber(metrics.averageHoldDays)}</div>
              </div>
            </div>

            {(metrics.totalReturnPct == null || metrics.maxDrawdownPct == null || metrics.sharpeRatio == null) ? (
              <div className="alert alert-warning border-0 py-2 px-3 mb-3 small">
                {t('backtest.metricGapHint')}
              </div>
            ) : null}

            <div className="small text-muted-custom mb-2">{t('backtest.tradeSummary')}</div>
            <div className="strategy-trade-list">
              {result?.trades.map((trade, index) => (
                <div key={`${trade.openDate}-${index}`} className="strategy-trade-item">
                  <span>{trade.openDate}</span>
                  <span>{trade.closeDate ?? t('backtest.openTrade')}</span>
                  <span>{trade.direction}</span>
                  <span>{trade.openReason}</span>
                </div>
              ))}
              {result?.trades.length === 0 ? (
                <div className="text-muted-custom small">{t('backtest.noTrades')}</div>
              ) : null}
            </div>
          </>
        ) : (
          <div className="text-muted-custom small">{t('backtest.modalEmptyHint')}</div>
        )}
      </div>
    </div>
  );
}
