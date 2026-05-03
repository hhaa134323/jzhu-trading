import { useEffect, useMemo, useRef, useState } from 'react';
import { createStrategyTemplate, saveStrategyTemplateVersion } from '../api';
import { useI18n } from '../i18n';
import type {
  CreateStrategyTemplateRequest,
  SaveStrategyTemplateVersionRequest,
  SearchFormValues,
  StrategyDefinition,
  StrategyDraft,
  StrategyParameters,
  WorkbenchModel,
} from '../types';

interface StrategyWorkbenchProps {
  searchForm: SearchFormValues;
  canRunBacktest: boolean;
  backtestRunning: boolean;
  onRunBacktest: (strategy: StrategyDraft) => void;
  onSelectedStrategyChange?: (strategy: StrategyDraft | null) => void;
}

const DEFAULT_OWNER = 'demo-user';
const DEFAULT_MODEL: WorkbenchModel = 'ChatGPT (gpt-5.4)';

const MODEL_OPTIONS: WorkbenchModel[] = ['ChatGPT (gpt-5.4)', 'Claude 3.7 Sonnet', 'DeepSeek-R1'];

function buildCodeText(definition: StrategyDefinition) {
  return JSON.stringify(
    {
      engineType: definition.engineType,
      baseStrategyId: definition.baseStrategyId,
      parameters: definition.parameters ?? {},
    },
    null,
    2,
  );
}

function buildDefinition(baseStrategyId: string, parameters: StrategyParameters): StrategyDefinition {
  return {
    engineType: 'JAVA_BUILTIN_ADAPTER',
    baseStrategyId,
    parameters,
  };
}

function sanitizeParameters(input: Record<string, unknown> | undefined): StrategyParameters | undefined {
  if (!input) {
    return undefined;
  }

  const entries = Object.entries(input).filter(([, value]) => typeof value === 'number' && Number.isFinite(value));
  if (entries.length === 0) {
    return undefined;
  }

  return Object.fromEntries(entries) as StrategyParameters;
}

function parseCodeText(codeText: string): StrategyDefinition {
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

function createInitialDrafts(): StrategyDraft[] {
  return [
    {
      localId: 'mean-cross-long',
      name: '均线交叉-做多',
      description: '趋势跟随，适合均线多头排列后的入场',
      ownerId: DEFAULT_OWNER,
      model: DEFAULT_MODEL,
      buyStrategy: 'MA5 上穿 MA20 且成交量放大时买入',
      sellStrategy: 'MA5 下穿 MA20 或止损触发时卖出',
      changeNote: 'init',
      codeText: buildCodeText(
        buildDefinition('maCrossLong', {
          closeMaFast: 10,
          closeMaSlow: 20,
        }),
      ),
    },
    {
      localId: 'macd-cross-long',
      name: 'MACD金叉死叉-做多',
      description: 'MACD 与均线过滤结合的趋势入场',
      ownerId: DEFAULT_OWNER,
      model: DEFAULT_MODEL,
      buyStrategy: 'DIF 上穿 DEA 且价格站上均线时买入',
      sellStrategy: 'DIF 下穿 DEA 或跌破均线时卖出',
      changeNote: 'init',
      codeText: buildCodeText(
        buildDefinition('maCrossLong', {
          closeMaFast: 10,
          closeMaSlow: 20,
        }),
      ),
    },
    {
      localId: 'rsi-rebound-long',
      name: 'RSI超卖反弹-做多',
      description: '超卖区反弹确认后入场',
      ownerId: DEFAULT_OWNER,
      model: DEFAULT_MODEL,
      buyStrategy: 'RSI 低于 20 且收盘站稳短均线时买入',
      sellStrategy: 'RSI 回升失败或价格跌破短均线时卖出',
      changeNote: 'init',
      codeText: buildCodeText(
        buildDefinition('bollReversionLong', {
          pullbackMaPeriod: 20,
          closeMaFast: 20,
          closeMaSlow: 20,
        }),
      ),
    },
    {
      localId: 'boll-breakout-long',
      name: '布林带突破-做多',
      description: '放量突破上轨后顺势入场',
      ownerId: DEFAULT_OWNER,
      model: DEFAULT_MODEL,
      buyStrategy: '放量突破布林带上轨时买入',
      sellStrategy: '回落到中轨下方或止损触发时卖出',
      changeNote: 'init',
      codeText: buildCodeText(
        buildDefinition('donchianBreakoutLong', {
          breakoutLookbackBars: 20,
          pullbackMaPeriod: 10,
        }),
      ),
    },
  ];
}

function mergeDraft(draft: StrategyDraft, patch: Partial<StrategyDraft>) {
  return { ...draft, ...patch };
}

export default function StrategyWorkbench({
  searchForm,
  canRunBacktest,
  backtestRunning,
  onRunBacktest,
  onSelectedStrategyChange,
}: StrategyWorkbenchProps) {
  const { t } = useI18n();
  const [drafts, setDrafts] = useState<StrategyDraft[]>(() => createInitialDrafts());
  const [selectedId, setSelectedId] = useState(drafts[0]?.localId ?? '');
  const [busy, setBusy] = useState<'validate' | 'save' | 'ai' | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const gutterRef = useRef<HTMLDivElement | null>(null);

  const selectedDraft = useMemo(() => drafts.find((item) => item.localId === selectedId) ?? drafts[0] ?? null, [drafts, selectedId]);

  useEffect(() => {
    onSelectedStrategyChange?.(selectedDraft ?? null);
  }, [onSelectedStrategyChange, selectedDraft]);

  const lineNumbers = useMemo(() => {
    const lineCount = selectedDraft ? selectedDraft.codeText.split(/\r?\n/).length : 1;
    return Array.from({ length: lineCount }, (_, index) => index + 1);
  }, [selectedDraft]);

  const syncGutterScroll = (scrollTop: number) => {
    if (gutterRef.current) {
      gutterRef.current.scrollTop = scrollTop;
    }
  };

  const updateSelectedDraft = (patch: Partial<StrategyDraft>) => {
    setDrafts((current) => current.map((draft) => (draft.localId === selectedId ? mergeDraft(draft, patch) : draft)));
  };

  const handleCreateDraft = () => {
    const localId = `draft-${Date.now()}`;
    const nextDraft: StrategyDraft = {
      localId,
      name: t('workbench.untitledStrategy'),
      description: t('workbench.untitledDescription'),
      ownerId: DEFAULT_OWNER,
      model: DEFAULT_MODEL,
      buyStrategy: '',
      sellStrategy: '',
      changeNote: 'init',
      codeText: buildCodeText(
        buildDefinition('maCrossLong', {
          closeMaFast: 10,
          closeMaSlow: 20,
        }),
      ),
    };

    setDrafts((current) => [nextDraft, ...current]);
    setSelectedId(localId);
    setNotice(t('workbench.draftCreated'));
    setError(null);
  };

  const handleValidate = async () => {
    if (!selectedDraft) {
      return;
    }

    setBusy('validate');
    setNotice(null);
    setError(null);
    try {
      const definition = parseCodeText(selectedDraft.codeText);
      setNotice(
        t('workbench.validationPassed', {
          baseStrategyId: definition.baseStrategyId,
        }),
      );
    } catch (validationError) {
      const message = validationError instanceof Error ? validationError.message : t('workbench.validationFailed');
      setError(message);
    } finally {
      setBusy(null);
    }
  };

  const handleAiPlaceholder = async (label: string) => {
    setBusy('ai');
    setNotice(t('workbench.aiPlaceholder', { label }));
    setError(null);
    setBusy(null);
  };

  const handleSave = async () => {
    if (!selectedDraft) {
      return;
    }

    setBusy('save');
    setNotice(null);
    setError(null);

    try {
      const definition = parseCodeText(selectedDraft.codeText);
      const description = [selectedDraft.buyStrategy.trim(), selectedDraft.sellStrategy.trim()].filter(Boolean).join('\n');
      const ownerId = selectedDraft.ownerId.trim() || DEFAULT_OWNER;
      const changeNote = selectedDraft.changeNote.trim() || 'init';

      if (!selectedDraft.templateId) {
        const request: CreateStrategyTemplateRequest = {
          name: selectedDraft.name.trim(),
          description: description || selectedDraft.description.trim() || undefined,
          ownerId,
          initialDefinition: definition,
          changeNote,
        };

        const detail = await createStrategyTemplate(request);
        setDrafts((current) =>
          current.map((draft) =>
            draft.localId === selectedDraft.localId
              ? {
                  ...draft,
                  templateId: detail.templateId,
                  latestVersion: detail.latestVersion ?? 1,
                  name: detail.name,
                  description: detail.description ?? draft.description,
                  ownerId,
                  codeText: buildCodeText(definition),
                }
              : draft,
          ),
        );
        setNotice(t('workbench.savedCreated', { templateId: detail.templateId }));
        return;
      }

      const request: SaveStrategyTemplateVersionRequest = {
        definition,
        changeNote,
        createdBy: ownerId,
      };
      const detail = await saveStrategyTemplateVersion(selectedDraft.templateId, request);

      setDrafts((current) =>
        current.map((draft) =>
          draft.localId === selectedDraft.localId
            ? {
                ...draft,
                latestVersion: detail.latestVersion ?? draft.latestVersion,
                ownerId,
                codeText: buildCodeText(definition),
              }
            : draft,
        ),
      );
      setNotice(t('workbench.savedUpdated', { version: detail.latestVersion ?? selectedDraft.latestVersion ?? 1 }));
    } catch (saveError) {
      const message = saveError instanceof Error ? saveError.message : t('workbench.saveFailed');
      setError(message);
    } finally {
      setBusy(null);
    }
  };

  if (!selectedDraft) {
    return null;
  }

  return (
    <div className="strategy-workbench">
      <section className="strategy-column strategy-list-column panel p-3">
        <div className="d-flex align-items-start justify-content-between gap-3 mb-3">
          <div>
            <div className="fs-5 fw-semibold mb-1">{t('workbench.strategyList')}</div>
            <div className="text-muted-custom small">{t('workbench.strategyListHint')}</div>
          </div>
        </div>

        {!canRunBacktest ? <div className="text-muted-custom small mb-3">{t('workbench.backtestNeedQuery')}</div> : null}

        <div className="strategy-list-scroll">
          {drafts.map((draft) => {
            const active = draft.localId === selectedId;
            return (
              <button
                key={draft.localId}
                type="button"
                className={`strategy-list-item ${active ? 'active' : ''}`}
                onClick={() => {
                  setSelectedId(draft.localId);
                  setNotice(null);
                  setError(null);
                }}
              >
                <span className="strategy-list-title">{draft.name}</span>
                <span className="strategy-list-subtitle">{draft.description}</span>
                <span className={`strategy-list-badge ${draft.templateId ? 'saved' : 'draft'}`}>
                  {draft.templateId ? t('workbench.savedBadge', { version: draft.latestVersion ?? 1 }) : t('workbench.draftBadge')}
                </span>
              </button>
            );
          })}
        </div>

        <div className="strategy-list-actions mt-3">
          <button type="button" className="btn btn-sm btn-brand-blue" onClick={handleCreateDraft}>
            {t('workbench.newStrategy')}
          </button>
          <button
            type="button"
            className="btn btn-sm btn-brand-orange"
            disabled={!canRunBacktest || !selectedDraft || backtestRunning}
            onClick={() => {
              if (!selectedDraft) {
                return;
              }
              onRunBacktest(selectedDraft);
            }}
          >
            {backtestRunning ? t('backtest.running') : t('workbench.runBacktest')}
          </button>
        </div>
      </section>

      <section className="strategy-column strategy-editor-column panel p-3">
        <div className="d-flex align-items-start justify-content-between gap-3 mb-3">
          <div>
            <div className="fs-5 fw-semibold mb-1">{t('workbench.editorTitle')}</div>
            <div className="text-muted-custom small">{t('workbench.editorHint')}</div>
          </div>
          <div className="d-flex flex-wrap gap-2 justify-content-end">
            <button
              type="button"
              className="btn btn-sm btn-outline-light"
              disabled={busy !== null}
              onClick={() => handleAiPlaceholder(t('workbench.aiParseCode'))}
            >
              {t('workbench.aiParseCode')}
            </button>
            <button
              type="button"
              className="btn btn-sm btn-outline-light"
              disabled={busy !== null}
              onClick={() => handleAiPlaceholder(t('workbench.aiGenerateCode'))}
            >
              {t('workbench.aiGenerateCode')}
            </button>
          </div>
        </div>

        <div className="row g-3 mb-3">
          <div className="col-12 col-md-6">
            <label className="workbench-label">{t('workbench.strategyName')}</label>
            <input
              className="form-control"
              value={selectedDraft.name}
              onChange={(event) => updateSelectedDraft({ name: event.target.value })}
              placeholder={t('workbench.strategyNamePlaceholder')}
            />
          </div>
          <div className="col-12 col-md-3">
            <label className="workbench-label">{t('workbench.ownerId')}</label>
            <input
              className="form-control"
              value={selectedDraft.ownerId}
              onChange={(event) => updateSelectedDraft({ ownerId: event.target.value })}
              placeholder={DEFAULT_OWNER}
            />
          </div>
          <div className="col-12 col-md-3">
            <label className="workbench-label">{t('workbench.model')}</label>
            <select
              className="form-select"
              value={selectedDraft.model}
              title={t('workbench.model')}
              onChange={(event) => updateSelectedDraft({ model: event.target.value as WorkbenchModel })}
            >
              {MODEL_OPTIONS.map((model) => (
                <option key={model} value={model}>
                  {model}
                </option>
              ))}
            </select>
          </div>
          <div className="col-12 col-md-6">
            <label className="workbench-label">{t('workbench.buyStrategy')}</label>
            <textarea
              className="form-control workbench-textarea"
              rows={4}
              value={selectedDraft.buyStrategy}
              onChange={(event) => updateSelectedDraft({ buyStrategy: event.target.value })}
              placeholder={t('workbench.buyStrategyPlaceholder')}
            />
          </div>
          <div className="col-12 col-md-6">
            <label className="workbench-label">{t('workbench.sellStrategy')}</label>
            <textarea
              className="form-control workbench-textarea"
              rows={4}
              value={selectedDraft.sellStrategy}
              onChange={(event) => updateSelectedDraft({ sellStrategy: event.target.value })}
              placeholder={t('workbench.sellStrategyPlaceholder')}
            />
          </div>
          <div className="col-12 col-md-8">
            <label className="workbench-label">{t('workbench.changeNote')}</label>
            <input
              className="form-control"
              value={selectedDraft.changeNote}
              onChange={(event) => updateSelectedDraft({ changeNote: event.target.value })}
              placeholder="init"
            />
          </div>
          <div className="col-12 col-md-4 d-flex align-items-end">
            <div className="strategy-meta-block w-100">
              <div className="text-muted-custom small mb-1">{t('workbench.versionMeta')}</div>
              <div className="fw-semibold">
                {selectedDraft.templateId ? `${selectedDraft.templateId} · v${selectedDraft.latestVersion ?? 1}` : t('workbench.unsavedDraft')}
              </div>
            </div>
          </div>
        </div>

        <div className="d-flex align-items-center justify-content-between mb-2">
          <label className="workbench-label mb-0">{t('workbench.strategyCode')}</label>
          <div className="text-muted-custom small">{t('workbench.codeHint')}</div>
        </div>

        <div className="code-editor-shell mb-3">
          <div ref={gutterRef} className="code-editor-gutter">
            <div className="code-editor-gutter-inner">
              {lineNumbers.map((lineNumber) => (
                <div key={lineNumber} className="code-editor-line-number">
                  {lineNumber}
                </div>
              ))}
            </div>
          </div>
          <textarea
            className="code-editor-textarea"
            value={selectedDraft.codeText}
            title={t('workbench.strategyCode')}
            placeholder={t('workbench.strategyCode')}
            spellCheck={false}
            onScroll={(event) => syncGutterScroll(event.currentTarget.scrollTop)}
            onChange={(event) => updateSelectedDraft({ codeText: event.target.value })}
          />
        </div>

        <div className="d-flex flex-wrap align-items-center gap-2">
          <button type="button" className="btn btn-sm btn-outline-light" disabled={busy !== null} onClick={handleValidate}>
            {busy === 'validate' ? t('workbench.validating') : t('workbench.validate')}
          </button>
          <button type="button" className="btn btn-sm btn-brand-orange" disabled={busy !== null} onClick={handleSave}>
            {busy === 'save' ? t('workbench.saving') : t('workbench.save')}
          </button>
        </div>

        {notice ? <div className="alert alert-success border-0 mt-3 mb-0">{notice}</div> : null}
        {error ? <div className="alert alert-danger border-0 mt-3 mb-0">{error}</div> : null}
      </section>

      <section className="strategy-column strategy-doc-column panel p-3">
        <div className="fs-5 fw-semibold mb-3">{t('workbench.docsTitle')}</div>
        <div className="strategy-doc-scroll">
          <div className="strategy-doc-block">
            <div className="strategy-doc-heading">{t('workbench.docsSectionIntroTitle')}</div>
            <p className="strategy-doc-text">{t('workbench.docsSectionIntroBody')}</p>
          </div>

          <div className="strategy-doc-block">
            <div className="strategy-doc-heading">{t('workbench.docsSectionParamsTitle')}</div>
            <table className="strategy-doc-table">
              <tbody>
                <tr>
                  <td>engineType</td>
                  <td>{t('workbench.docsEngineType')}</td>
                </tr>
                <tr>
                  <td>baseStrategyId</td>
                  <td>{t('workbench.docsBaseStrategyId')}</td>
                </tr>
                <tr>
                  <td>parameters</td>
                  <td>{t('workbench.docsParameters')}</td>
                </tr>
                <tr>
                  <td>buyStrategy</td>
                  <td>{t('workbench.docsBuyStrategy')}</td>
                </tr>
                <tr>
                  <td>sellStrategy</td>
                  <td>{t('workbench.docsSellStrategy')}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div className="strategy-doc-block">
            <div className="strategy-doc-heading">{t('workbench.docsSectionRulesTitle')}</div>
            <ul className="strategy-doc-list">
              <li>{t('workbench.docsRuleSave')}</li>
              <li>{t('workbench.docsRuleValidate')}</li>
              <li>{t('workbench.docsRuleBacktest')}</li>
            </ul>
          </div>

          <div className="strategy-doc-block mb-0">
            <div className="strategy-doc-heading">{t('workbench.docsSectionAiTitle')}</div>
            <p className="strategy-doc-text mb-0">{t('workbench.docsAiBody')}</p>
          </div>

          <div className="strategy-doc-block mb-0">
            <div className="strategy-doc-heading">{t('backtest.currentQuery')}</div>
            <p className="strategy-doc-text mb-0">
              {searchForm.symbol.toUpperCase()} · {t(`common.market.${searchForm.market}`)} · {t(`common.period.${searchForm.period}`)}
            </p>
          </div>
        </div>
      </section>
    </div>
  );
}
