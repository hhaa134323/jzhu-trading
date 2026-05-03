import { useEffect, useRef, useState, type RefObject } from 'react';
import { runBacktest } from '../api';
import { useI18n } from '../i18n';
import type { SearchFormValues, SimpleBacktestResponse, StrategyDraft } from '../types';
import { buildBacktestRequest, formatPercent } from './backtestUtils.ts';

interface InlineBacktestPanelProps {
  visible: boolean;
  runToken: number;
  strategy: StrategyDraft | null;
  form: SearchFormValues;
  requestTabId: string | null;
  result: SimpleBacktestResponse | null;
  anchorRef?: RefObject<HTMLDivElement | null>;
  onApplied: (tabId: string, result: SimpleBacktestResponse) => void;
  onFinished: () => void;
  backtestRunning: boolean;
}

export default function InlineBacktestPanel({
  visible,
  runToken,
  strategy,
  form,
  requestTabId,
  result,
  anchorRef,
  onApplied,
  onFinished,
  backtestRunning,
}: InlineBacktestPanelProps) {
  const { t } = useI18n();
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const handledRunTokenRef = useRef(0);

  useEffect(() => {
    if (!visible || !strategy || !requestTabId) {
      return;
    }

    if (runToken <= handledRunTokenRef.current) {
      return;
    }

    if (running || backtestRunning) {
      handledRunTokenRef.current = runToken;
      return;
    }

    handledRunTokenRef.current = runToken;
    setRunning(true);
    setError(null);

    void (async () => {
      try {
        const request = buildBacktestRequest(strategy, form);
        const response = await runBacktest(request);
        onApplied(requestTabId, response);
      } catch (err) {
        const message = err instanceof Error ? err.message : t('backtest.runFailed');
        setError(message);
      } finally {
        setRunning(false);
        onFinished();
      }
    })();
  }, [backtestRunning, form, onApplied, onFinished, requestTabId, runToken, running, strategy, t, visible]);

  const isLoading = visible && strategy && requestTabId ? running || backtestRunning || runToken > handledRunTokenRef.current : false;
  const totalReturnPct = result?.metrics?.totalReturnPct;
  const displayedTotalReturn = isLoading ? '--' : formatPercent(totalReturnPct);

  if (!visible) {
    return null;
  }

  return (
    <div ref={anchorRef} className="panel-soft p-3 rounded-3 mt-2 strategy-inline-backtest-panel">
      <div className="d-flex align-items-center justify-content-between gap-3">
        <div>
          <div className="text-muted-custom small">{t('backtest.metricTotalReturn')}</div>
          <div className="fs-4 fw-semibold">{displayedTotalReturn}</div>
        </div>
        <div className="text-end small">
          {isLoading ? <div className="text-warning fw-semibold">{t('backtest.running')}</div> : null}
          {!isLoading && error ? <div className="text-danger fw-semibold">{t('backtest.runFailed')}</div> : null}
        </div>
      </div>
      {isLoading ? (
        <div className="d-flex align-items-center gap-2 mt-3 text-muted-custom small">
          <div className="spinner-border spinner-border-sm text-primary" role="status" aria-hidden="true" />
          <span>{t('backtest.running')}</span>
        </div>
      ) : null}
      {error ? <div className="text-danger small mt-3">{error}</div> : null}
    </div>
  );
}
