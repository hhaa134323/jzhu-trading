import { useMemo, useState } from 'react';
import { api } from '../api';
import BacktestPanel from '../components/BacktestPanel';
import KlineChart from '../components/KlineChart';
import SearchBar from '../components/SearchBar';
import StockTabs from '../components/StockTabs';
import StrategyWorkbench from '../components/StrategyWorkbench';
import { useI18n } from '../i18n';
import type {
  KlineWithIndicatorsResponse,
  SearchFormValues,
  SimpleBacktestResponse,
  StockTab,
  StrategyDraft,
} from '../types';

const PREF_KEY = 'chart.preferences.v1';

type VisibilityState = {
  kline: boolean;
  volume: boolean;
  ma: boolean;
  boll: boolean;
  macd: boolean;
  rsi: boolean;
};

type ConfigState = {
  ma: { ma5: boolean; ma10: boolean; ma20: boolean; ma30: boolean; ma60: boolean };
  macd: { dif: boolean; dea: boolean; hist: boolean };
  boll: { upper: boolean; middle: boolean; lower: boolean; band: boolean };
};

const DEFAULT_VISIBILITY: VisibilityState = {
  kline: true,
  volume: true,
  ma: true,
  boll: true,
  macd: true,
  rsi: true,
};

const DEFAULT_CONFIG: ConfigState = {
  ma: { ma5: true, ma10: true, ma20: true, ma30: false, ma60: false },
  macd: { dif: true, dea: true, hist: true },
  boll: { upper: true, middle: true, lower: true, band: true },
};

function loadPrefs() {
  const fallback = { visibility: DEFAULT_VISIBILITY, config: DEFAULT_CONFIG };
  try {
    const raw = localStorage.getItem(PREF_KEY);
    if (!raw) {
      return fallback;
    }
    const parsed = JSON.parse(raw) as { visibility?: VisibilityState; config?: ConfigState };
    return {
      visibility: { ...DEFAULT_VISIBILITY, ...(parsed.visibility ?? {}) },
      config: {
        ma: { ...DEFAULT_CONFIG.ma, ...(parsed.config?.ma ?? {}) },
        macd: { ...DEFAULT_CONFIG.macd, ...(parsed.config?.macd ?? {}) },
        boll: { ...DEFAULT_CONFIG.boll, ...(parsed.config?.boll ?? {}) },
      },
    };
  } catch {
    return fallback;
  }
}

function getDefaultDates() {
  const end = new Date();
  const start = new Date();
  start.setFullYear(end.getFullYear() - 2);

  const format = (date: Date) => date.toISOString().slice(0, 10);

  return {
    startDate: format(start),
    endDate: format(end),
  };
}

function createTabId(values: SearchFormValues) {
  return [values.symbol, values.market, values.period, values.startDate, values.endDate].join('|');
}

export default function KlinePage() {
  const { t } = useI18n();
  const defaults = useMemo(() => getDefaultDates(), []);
  const prefs = useMemo(() => loadPrefs(), []);
  const [form, setForm] = useState<SearchFormValues>({
    symbol: 'TSLA',
    market: 'us',
    period: 'daily',
    startDate: defaults.startDate,
    endDate: defaults.endDate,
  });
  const [tabs, setTabs] = useState<StockTab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [visibility, setVisibility] = useState<VisibilityState>(prefs.visibility);
  const [config, setConfig] = useState<ConfigState>(prefs.config);
  const [openConfig, setOpenConfig] = useState<'ma' | 'macd' | 'boll' | null>(null);
  const [backtestResults, setBacktestResults] = useState<Record<string, SimpleBacktestResponse>>({});
  const [backtestOpen, setBacktestOpen] = useState(false);
  const [backtestStrategy, setBacktestStrategy] = useState<StrategyDraft | null>(null);
  const [workbenchOpen, setWorkbenchOpen] = useState(false);
  const [selectedStrategy, setSelectedStrategy] = useState<StrategyDraft | null>(null);

  const persistPrefs = (nextVisibility: VisibilityState, nextConfig: ConfigState) => {
    localStorage.setItem(PREF_KEY, JSON.stringify({ visibility: nextVisibility, config: nextConfig }));
  };

  const activeTab = tabs.find((tab) => tab.id === activeTabId) ?? tabs[0] ?? null;
  const totalCount = activeTab?.totalCount ?? 0;

  const marketLabel = (market: SearchFormValues['market']) => t(`common.market.${market}`);

  const handleSearch = async () => {
    const payload = { ...form, symbol: form.symbol.trim().toUpperCase() };
    if (!payload.symbol) {
      setError(t('klinePage.inputSymbolFirst'));
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const response = await api.get<KlineWithIndicatorsResponse>('/kline', { params: payload });
      const data = response.data;
      const klines = data?.klines ?? [];
      const id = createTabId(payload);
      const tab: StockTab = {
        id,
        label: `${payload.symbol} ${marketLabel(payload.market)}`,
        ...payload,
        klines,
        indicators: data.indicators,
        totalCount: data.totalCount,
      };

      setTabs((current) => {
        const next = current.filter((item) => item.id !== id);
        return [tab, ...next];
      });
      setActiveTabId(id);
      setForm(payload);
      setBacktestResults((current) => {
        const next = { ...current };
        delete next[id];
        return next;
      });
    } catch (err) {
      const message = err instanceof Error ? err.message : t('klinePage.queryFailed');
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  const handleSelectTab = (tabId: string) => {
    setActiveTabId(tabId);
    const tab = tabs.find((item) => item.id === tabId);
    if (tab) {
      setForm({
        symbol: tab.symbol,
        market: tab.market,
        period: tab.period,
        startDate: tab.startDate,
        endDate: tab.endDate,
      });
    }
  };

  const handleRemoveTab = (tabId: string) => {
    setTabs((current) => {
      const next = current.filter((item) => item.id !== tabId);
      if (activeTabId === tabId) {
        setActiveTabId(next[0]?.id ?? null);
      }
      return next;
    });
    setBacktestResults((current) => {
      const next = { ...current };
      delete next[tabId];
      return next;
    });
  };

  const clearCurrentBacktest = () => {
    if (!activeTab) {
      return;
    }
    setBacktestResults((current) => {
      const next = { ...current };
      delete next[activeTab.id];
      return next;
    });
  };

  const activeTrades = activeTab ? backtestResults[activeTab.id]?.trades ?? [] : [];

  return (
    <div className="container-xxl strategy-page-shell">
      <section className="panel p-3 p-lg-4">
        <SearchBar value={form} onChange={setForm} onSearch={handleSearch} loading={loading} />

        <StockTabs tabs={tabs} activeTabId={activeTabId} onSelect={handleSelectTab} onRemove={handleRemoveTab} />

        <div className="d-flex align-items-center justify-content-between mb-3 px-1">
          <div className="d-flex align-items-center gap-2 flex-wrap">
            <button type="button" className="btn btn-sm btn-brand-blue" onClick={() => setWorkbenchOpen(true)}>
              {t('workbench.modeEdit')}
            </button>
            <button
              type="button"
              className="btn btn-sm btn-brand-orange"
              disabled={!activeTab || !selectedStrategy}
              onClick={() => {
                if (!selectedStrategy || !activeTab) {
                  return;
                }
                setBacktestStrategy(selectedStrategy);
                setBacktestOpen(true);
              }}
            >
              {t('workbench.modeBacktest')}
            </button>
          </div>
          <div className="d-flex align-items-center gap-2 flex-wrap position-relative">
            <div className="text-muted-custom small">{t('klinePage.totalCount', { count: totalCount })}</div>

            <span className="text-muted-custom small ms-2">{t('klinePage.chartFilter')}</span>
            {[
              { key: 'kline', label: t('chart.legend.kline') },
              { key: 'volume', label: t('chart.legend.volume') },
              { key: 'ma', label: t('chart.legend.ma') },
              { key: 'boll', label: t('chart.legend.boll') },
              { key: 'macd', label: t('chart.legend.macd') },
              { key: 'rsi', label: t('chart.legend.rsi') },
            ].map((item) => {
              const key = item.key as keyof VisibilityState;
              const enabled = visibility[key];
              return (
                <button
                  key={item.key}
                  type="button"
                  className={`btn btn-sm ${enabled ? 'btn-brand-blue' : 'btn-outline-light'}`}
                  onClick={() => {
                    const nextVisibility = { ...visibility, [key]: !enabled };
                    setVisibility(nextVisibility);
                    persistPrefs(nextVisibility, config);
                  }}
                >
                  {item.label}
                </button>
              );
            })}

            <button
              type="button"
              className="btn btn-sm btn-outline-light"
              onClick={() => setOpenConfig((s) => (s === 'ma' ? null : 'ma'))}
            >
              {t('klinePage.maSettings')}
            </button>
            <button
              type="button"
              className="btn btn-sm btn-outline-light"
              onClick={() => setOpenConfig((s) => (s === 'macd' ? null : 'macd'))}
            >
              {t('klinePage.macdSettings')}
            </button>
            <button
              type="button"
              className="btn btn-sm btn-outline-light"
              onClick={() => setOpenConfig((s) => (s === 'boll' ? null : 'boll'))}
            >
              {t('klinePage.bollSettings')}
            </button>

            {openConfig === 'ma' ? (
              <div className="panel-soft p-2 position-absolute end-0 top-100 mt-2 strategy-config-popover">
                {[
                  { key: 'ma5', label: t('klinePage.ma5') },
                  { key: 'ma10', label: t('klinePage.ma10') },
                  { key: 'ma20', label: t('klinePage.ma20') },
                  { key: 'ma30', label: t('klinePage.ma30') },
                  { key: 'ma60', label: t('klinePage.ma60') },
                ].map((item) => (
                  <label key={item.key} className="d-flex align-items-center gap-2 mb-1 small">
                    <input
                      type="checkbox"
                      checked={config.ma[item.key as keyof typeof config.ma]}
                      onChange={(e) => {
                        const nextConfig = {
                          ...config,
                          ma: { ...config.ma, [item.key]: e.target.checked },
                        };
                        setConfig(nextConfig);
                        persistPrefs(visibility, nextConfig);
                      }}
                    />
                    <span>{item.label}</span>
                  </label>
                ))}
              </div>
            ) : null}

            {openConfig === 'macd' ? (
              <div className="panel-soft p-2 position-absolute end-0 top-100 mt-2 strategy-config-popover">
                {[
                  { key: 'dif', label: t('klinePage.macdDif') },
                  { key: 'dea', label: t('klinePage.macdDea') },
                  { key: 'hist', label: t('klinePage.macdHist') },
                ].map((item) => (
                  <label key={item.key} className="d-flex align-items-center gap-2 mb-1 small">
                    <input
                      type="checkbox"
                      checked={config.macd[item.key as keyof typeof config.macd]}
                      onChange={(e) => {
                        const nextConfig = {
                          ...config,
                          macd: { ...config.macd, [item.key]: e.target.checked },
                        };
                        setConfig(nextConfig);
                        persistPrefs(visibility, nextConfig);
                      }}
                    />
                    <span>{item.label}</span>
                  </label>
                ))}
              </div>
            ) : null}

            {openConfig === 'boll' ? (
              <div className="panel-soft p-2 position-absolute end-0 top-100 mt-2 strategy-config-popover">
                {[
                  { key: 'upper', label: t('klinePage.bollUpper') },
                  { key: 'middle', label: t('klinePage.bollMiddle') },
                  { key: 'lower', label: t('klinePage.bollLower') },
                  { key: 'band', label: t('klinePage.bollBand') },
                ].map((item) => (
                  <label key={item.key} className="d-flex align-items-center gap-2 mb-1 small">
                    <input
                      type="checkbox"
                      checked={config.boll[item.key as keyof typeof config.boll]}
                      onChange={(e) => {
                        const nextConfig = {
                          ...config,
                          boll: { ...config.boll, [item.key]: e.target.checked },
                        };
                        setConfig(nextConfig);
                        persistPrefs(visibility, nextConfig);
                      }}
                    />
                    <span>{item.label}</span>
                  </label>
                ))}
              </div>
            ) : null}
          </div>
        </div>

        {error ? (
          <div className="alert alert-danger border-0 shadow-sm mb-3" role="alert">
            {error}
          </div>
        ) : null}

        <div className="panel-soft p-2 p-md-3">
          {loading ? (
            <div className="d-flex align-items-center justify-content-center py-5 my-5">
              <div className="text-center">
                <div className="spinner-border text-primary mb-3" role="status" aria-hidden="true" />
                <div className="text-muted-custom">{t('klinePage.loadingData')}</div>
              </div>
            </div>
          ) : activeTab ? (
            activeTab.klines.length > 0 ? (
              <KlineChart
                symbol={activeTab.symbol}
                klines={activeTab.klines}
                indicators={activeTab.indicators}
                visibility={visibility}
                maConfig={config.ma}
                macdConfig={config.macd}
                bollConfig={config.boll}
                backtestTrades={activeTrades}
              />
            ) : (
              <div className="text-center py-5 my-5 text-muted-custom">{t('klinePage.noData')}</div>
            )
          ) : (
            <div className="text-center py-5 my-5 text-muted-custom">{t('klinePage.inputHint')}</div>
          )}
        </div>

        {activeTab ? <div className="text-muted-custom small mt-2">{t('backtest.totalTrades', { count: activeTrades.length })}</div> : null}
      </section>

      {workbenchOpen ? (
        <div className="strategy-modal-backdrop" role="presentation" onClick={() => setWorkbenchOpen(false)}>
          <div className="strategy-modal panel strategy-editor-modal" role="dialog" aria-modal="true" onClick={(event) => event.stopPropagation()}>
            <div className="d-flex align-items-start justify-content-between gap-3 mb-3">
              <div>
                <div className="fs-5 fw-semibold mb-1">{t('workbench.pageTitle')}</div>
                <div className="text-muted-custom small">{t('workbench.editorModalHint')}</div>
              </div>
              <button type="button" className="btn btn-sm btn-outline-light" onClick={() => setWorkbenchOpen(false)}>
                {t('backtest.close')}
              </button>
            </div>

            <StrategyWorkbench
              searchForm={form}
              canRunBacktest={Boolean(activeTab)}
              onSelectedStrategyChange={setSelectedStrategy}
              onRunBacktest={(strategy) => {
                if (!activeTab) {
                  return;
                }
                setBacktestStrategy(strategy);
                setBacktestOpen(true);
              }}
            />
          </div>
        </div>
      ) : null}

      <BacktestPanel
        open={backtestOpen}
        strategy={backtestStrategy}
        form={form}
        onClose={() => setBacktestOpen(false)}
        onApplied={(result) => {
          if (!activeTab) {
            return;
          }
          setBacktestResults((current) => ({ ...current, [activeTab.id]: result }));
        }}
        onClear={clearCurrentBacktest}
      />
    </div>
  );
}
