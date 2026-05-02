import type { StockTab } from '../types';
import { useI18n } from '../i18n';

interface StockTabsProps {
  tabs: StockTab[];
  activeTabId: string | null;
  onSelect: (tabId: string) => void;
  onRemove: (tabId: string) => void;
}

export default function StockTabs({ tabs, activeTabId, onSelect, onRemove }: StockTabsProps) {
  const { t } = useI18n();

  if (tabs.length === 0) {
    return null;
  }

  return (
    <div className="panel-soft p-3 mb-3">
      <div className="d-flex flex-wrap gap-2">
        {tabs.map((tab) => {
          const active = tab.id === activeTabId;
          return (
            <div key={tab.id} className={`stock-tab ${active ? 'active' : ''}`}>
              <button
                type="button"
                className="btn btn-link text-decoration-none p-0 text-reset fw-semibold"
                onClick={() => onSelect(tab.id)}
              >
                {tab.symbol} {t(`common.market.${tab.market}`)}
              </button>
              <button
                type="button"
                className="close-btn"
                aria-label={t('tabs.remove', { symbol: tab.symbol })}
                onClick={() => onRemove(tab.id)}
              >
                ×
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
