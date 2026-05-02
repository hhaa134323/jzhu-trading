import { useI18n } from '../i18n';

export default function Navbar() {
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
          <ul className="navbar-nav ms-auto align-items-lg-center gap-lg-2 mt-3 mt-lg-0">
            <li className="nav-item">
              <button className="nav-link active text-white fw-semibold btn btn-link px-2" type="button">
                {t('navbar.klineBacktest')}
              </button>
            </li>
            <li className="nav-item d-flex align-items-center gap-1 ms-lg-2">
              <span className="text-muted-custom small me-1">{t('common.langLabel')}</span>
              <button
                type="button"
                className={`btn btn-sm ${language === 'zh-CN' ? 'btn-brand-blue' : 'btn-outline-light'}`}
                onClick={() => setLanguage('zh-CN')}
              >
                {t('common.zh')}
              </button>
              <button
                type="button"
                className={`btn btn-sm ${language === 'en-US' ? 'btn-brand-blue' : 'btn-outline-light'}`}
                onClick={() => setLanguage('en-US')}
              >
                {t('common.en')}
              </button>
            </li>
          </ul>
        </div>
      </div>
    </nav>
  );
}
