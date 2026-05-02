import { createContext, useContext, useMemo, useState, type PropsWithChildren } from 'react';
import zhCN from './zh-CN';
import enUS from './en-US';

export type Language = 'zh-CN' | 'en-US';

type Dict = typeof zhCN;

const DICTS: Record<Language, Dict> = {
  'zh-CN': zhCN,
  'en-US': enUS,
};

const STORAGE_KEY = 'app.language';

interface I18nContextValue {
  language: Language;
  setLanguage: (lang: Language) => void;
  t: (key: string, params?: Record<string, string | number>) => string;
}

const I18nContext = createContext<I18nContextValue | null>(null);

export function I18nProvider({ children }: PropsWithChildren) {
  const [language, setLanguageState] = useState<Language>(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved === 'zh-CN' || saved === 'en-US') {
      return saved;
    }
    return 'zh-CN';
  });

  const setLanguage = (lang: Language) => {
    setLanguageState(lang);
    localStorage.setItem(STORAGE_KEY, lang);
  };

  const t = (key: string, params?: Record<string, string | number>) => {
    const dict = DICTS[language] as Record<string, unknown>;
    const value = key.split('.').reduce<unknown>((acc, part) => {
      if (acc && typeof acc === 'object' && part in (acc as Record<string, unknown>)) {
        return (acc as Record<string, unknown>)[part];
      }
      return undefined;
    }, dict);

    if (typeof value !== 'string') {
      return key;
    }

    if (!params) {
      return value;
    }

    return Object.entries(params).reduce((text, [k, v]) => {
      return text.replace(`{${k}}`, String(v));
    }, value);
  };

  const contextValue = useMemo<I18nContextValue>(() => ({ language, setLanguage, t }), [language]);

  return <I18nContext.Provider value={contextValue}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const ctx = useContext(I18nContext);
  if (!ctx) {
    throw new Error('useI18n must be used inside I18nProvider');
  }
  return ctx;
}
