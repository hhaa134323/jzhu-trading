import { useI18n } from '../i18n';
import LanguageIcon from './icons/LanguageIcon';

export type NavTabKey = 'backtest' | 'history' | 'marketScan';

const NAV_TABS: Array<{ key: NavTabKey; i18nKey: string; disabled?: boolean }> = [
  { key: 'backtest', i18nKey: 'navbar.backtest' },
  { key: 'history', i18nKey: 'navbar.history', disabled: true },
  { key: 'marketScan', i18nKey: 'navbar.marketScan', disabled: true },
];

interface NavbarProps {
  activeTab: NavTabKey;
  onTabChange: (key: NavTabKey) => void;
}

export default function Navbar({ activeTab, onTabChange }: NavbarProps) {
  const { language, setLanguage, t } = useI18n();

  return (
    <nav className="navbar navbar-dark-custom navbar-expand-lg sticky-top">
      <div className="container-fluid px-3 px-lg-4 py-3">
        <a className="navbar-brand fw-bold fs-4 text-white" href="/">
          {t('navbar.brand')}
        </a>
        <button
          className="navbar-toggler border-0 shadow-none"
          type="button"
          data-bs-toggle="collapse"
          data-bs-target="#topNav"
          aria-controls="topNav"
          aria-expanded="false"
          aria-label={t('navbar.toggleNav')}
        >
          <span className="navbar-toggler-icon" />
        </button>
        <div className="collapse navbar-collapse" id="topNav">
          <ul className="navbar-nav me-auto align-items-lg-center mt-3 mt-lg-0">
            {NAV_TABS.map((tab) => (
              <li key={tab.key} className="nav-item">
                <button
                  type="button"
                  className={`nav-tab ${activeTab === tab.key ? 'active' : ''}`}
                  disabled={tab.disabled}
                  data-coming-soon={tab.disabled ? t('navbar.comingSoon') : undefined}
                  onClick={() => !tab.disabled && onTabChange(tab.key)}
                >
                  {t(tab.i18nKey)}
                </button>
              </li>
            ))}
          </ul>
          <ul className="navbar-nav align-items-lg-center mt-3 mt-lg-0">
            <li className="nav-item">
              <button
                type="button"
                className="lang-toggle"
                onClick={() => setLanguage(language === 'zh-CN' ? 'en-US' : 'zh-CN')}
                aria-label={t('common.langLabel')}
                title={language === 'zh-CN' ? 'Switch to English' : '切换到中文'}
              >
                <LanguageIcon size={14} />
                {language === 'zh-CN' ? '中' : 'EN'}
              </button>
            </li>
          </ul>
        </div>
      </div>
    </nav>
  );
}
