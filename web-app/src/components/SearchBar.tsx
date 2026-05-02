import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import { useI18n } from '../i18n';
import type { MarketCode, PeriodCode, SearchFormValues } from '../types';

interface SearchBarProps {
  value: SearchFormValues;
  loading?: boolean;
  onChange: (value: SearchFormValues) => void;
  onSearch: () => void;
}

export default function SearchBar({ value, loading = false, onChange, onSearch }: SearchBarProps) {
  const { t } = useI18n();
  const [form, setForm] = useState<SearchFormValues>(value);

  useEffect(() => {
    setForm(value);
  }, [value]);

  const updateField = <K extends keyof SearchFormValues>(key: K, nextValue: SearchFormValues[K]) => {
    const next = { ...form, [key]: nextValue };
    setForm(next);
    onChange(next);
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSearch();
  };

  return (
    <form className="panel p-3 p-lg-4 mb-3" onSubmit={handleSubmit}>
      <div className="row g-3 align-items-end">
        <div className="col-12 col-md-2">
          <label className="search-label" htmlFor="symbol">{t('search.symbol')}</label>
          <input
            id="symbol"
            className="form-control"
            placeholder={t('search.symbolPlaceholder')}
            value={form.symbol}
            onChange={(event: ChangeEvent<HTMLInputElement>) => updateField('symbol', event.target.value.toUpperCase())}
          />
        </div>
        <div className="col-6 col-md-2">
          <label className="search-label" htmlFor="market">{t('search.market')}</label>
          <select
            id="market"
            className="form-select"
            value={form.market}
            onChange={(event: ChangeEvent<HTMLSelectElement>) => updateField('market', event.target.value as MarketCode)}
          >
            <option value="us">{t('common.market.us')}</option>
            <option value="hk">{t('common.market.hk')}</option>
            <option value="cn">{t('common.market.cn')}</option>
          </select>
        </div>
        <div className="col-6 col-md-2">
          <label className="search-label" htmlFor="period">{t('search.period')}</label>
          <select
            id="period"
            className="form-select"
            value={form.period}
            onChange={(event: ChangeEvent<HTMLSelectElement>) => updateField('period', event.target.value as PeriodCode)}
          >
            <option value="daily">{t('common.period.daily')}</option>
            <option value="weekly">{t('common.period.weekly')}</option>
            <option value="monthly">{t('common.period.monthly')}</option>
          </select>
        </div>
        <div className="col-12 col-md-2">
          <label className="search-label" htmlFor="startDate">{t('search.startDate')}</label>
          <input
            id="startDate"
            className="form-control"
            type="date"
            value={form.startDate}
            onChange={(event: ChangeEvent<HTMLInputElement>) => updateField('startDate', event.target.value)}
          />
        </div>
        <div className="col-12 col-md-2">
          <label className="search-label" htmlFor="endDate">{t('search.endDate')}</label>
          <input
            id="endDate"
            className="form-control"
            type="date"
            value={form.endDate}
            onChange={(event: ChangeEvent<HTMLInputElement>) => updateField('endDate', event.target.value)}
          />
        </div>
        <div className="col-12 col-md-2 d-grid">
          <button type="submit" className="btn btn-brand-blue fw-semibold" disabled={loading}>
            {loading ? t('search.searching') : t('search.search')}
          </button>
        </div>
      </div>
    </form>
  );
}
