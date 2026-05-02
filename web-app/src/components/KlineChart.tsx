import { useEffect, useMemo, useRef } from 'react';
import * as echarts from 'echarts';
import type { EChartsOption } from 'echarts';
import { useI18n } from '../i18n';
import type { BacktestTradeDetail, IndicatorResponse, Kline } from '../types';

interface KlineChartProps {
  symbol: string;
  klines: Kline[];
  indicators: IndicatorResponse;
  visibility: {
    kline: boolean;
    volume: boolean;
    ma: boolean;
    boll: boolean;
    macd: boolean;
    rsi: boolean;
  };
  maConfig: {
    ma5: boolean;
    ma10: boolean;
    ma20: boolean;
    ma30: boolean;
    ma60: boolean;
  };
  macdConfig: {
    dif: boolean;
    dea: boolean;
    hist: boolean;
  };
  bollConfig: {
    upper: boolean;
    middle: boolean;
    lower: boolean;
    band: boolean;
  };
  backtestTrades?: BacktestTradeDetail[];
}

export default function KlineChart({ symbol, klines, indicators, visibility, maConfig, macdConfig, bollConfig, backtestTrades = [] }: KlineChartProps) {
  const { t } = useI18n();
  const chartRef = useRef<HTMLDivElement | null>(null);
  const chartInstanceRef = useRef<echarts.ECharts | null>(null);

  const formatVolumeAxisLabel = (value: number) => {
    const abs = Math.abs(value);
    if (abs >= 1_000_000_000) {
      return `${(value / 1_000_000_000).toFixed(1)}B`;
    }
    if (abs >= 1_000_000) {
      return `${(value / 1_000_000).toFixed(1)}M`;
    }
    if (abs >= 1_000) {
      return `${(value / 1_000).toFixed(0)}K`;
    }
    return `${value}`;
  };

  const option = useMemo<EChartsOption>(() => {
    const dates = klines.map((item) => item.date);
    const candleData = klines.map((item) => [item.open, item.close, item.low, item.high]);
    const volumes = klines.map((item) => ({
      value: item.volume,
      itemStyle: {
        color: item.close >= item.open ? '#ef4444' : '#22c55e',
      },
    }));
    const ma = indicators.ma;
    const macd = indicators.macd;
    const rsi = indicators.rsi;
    const boll = indicators.boll;
    const macdBars = macd.macdList.map((v) => ({
      value: v,
      itemStyle: { color: (v ?? 0) >= 0 ? '#ef4444' : '#22c55e' },
    }));

    const legendName = {
      kline: t('chart.legend.kline'),
      ma: t('chart.legend.ma'),
      boll: t('chart.legend.boll'),
      volume: t('chart.legend.volume'),
      macd: t('chart.legend.macd'),
      rsi: t('chart.legend.rsi'),
    };

    const legendSelected: Record<string, boolean> = {
      [legendName.kline]: visibility.kline,
      [legendName.ma]: visibility.ma,
      [legendName.boll]: visibility.boll,
      [legendName.volume]: visibility.volume,
      [legendName.macd]: visibility.macd,
      [legendName.rsi]: visibility.rsi,
    };

    const longTrades = backtestTrades.filter((trade) => trade.direction === 'LONG');
    const markPointData: any[] = [];
    const markAreaData: any[] = [];

    for (const trade of longTrades) {
      markPointData.push({
        name: t('chart.openLong'),
        coord: [trade.openDate, trade.openPrice],
        value: trade.openPrice,
        symbol: 'triangle',
        symbolRotate: 0,
        symbolSize: 15,
        symbolOffset: [0, 12],
        itemStyle: { color: '#facc15', borderColor: '#f59e0b', borderWidth: 1 },
        label: {
          show: true,
          position: 'bottom',
          color: '#fde68a',
          formatter: `${t('chart.openLong')} ${trade.openPrice.toFixed(2)}`,
        },
      });

      if (trade.closed && trade.closeDate) {
        markPointData.push({
          name: t('chart.closeLong'),
          coord: [trade.closeDate, trade.closePrice],
          value: trade.closePrice,
          symbol: 'triangle',
          symbolRotate: 180,
          symbolSize: 15,
          symbolOffset: [0, -12],
          itemStyle: { color: '#facc15', borderColor: '#f59e0b', borderWidth: 1 },
          label: {
            show: true,
            position: 'top',
            color: '#fde68a',
            formatter: `${t('chart.closeLong')} ${trade.closePrice.toFixed(2)}`,
          },
        });
      }

      const endDate = trade.closed && trade.closeDate ? trade.closeDate : dates[dates.length - 1];
      if (endDate) {
        markAreaData.push([
          { xAxis: trade.openDate },
          { xAxis: endDate },
        ]);
      }
    }

    return {
      backgroundColor: 'transparent',
      animation: true,
      title: {
        text: symbol || legendName.kline,
        left: 12,
        top: 8,
        textStyle: {
          color: '#e6edf3',
          fontSize: 18,
          fontWeight: 700,
        },
      },
      legend: {
        data: [legendName.kline, legendName.ma, legendName.boll, legendName.volume, legendName.macd, legendName.rsi],
        selected: legendSelected,
        top: 10,
        right: 16,
        textStyle: { color: '#8b949e' },
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'cross' },
        backgroundColor: 'rgba(13, 17, 23, 0.96)',
        borderColor: '#30363d',
        textStyle: { color: '#e6edf3' },
        formatter: (params) => {
          const list = Array.isArray(params) ? params : [params];
          const candle = list.find((item) => item.seriesType === 'candlestick');
          const volume = list.find((item) => item.seriesName === legendName.volume);
          const dif = list.find((item) => item.seriesId === 'dif-line');
          const dea = list.find((item) => item.seriesId === 'dea-line');
          const macdBar = list.find((item) => item.seriesId === 'macd-bar');
          const rsi6 = list.find((item) => item.seriesId === 'rsi6-line');
          if (!candle) {
            return '';
          }
          const [open, close, high, low] = candle.data as number[];
          const dateLabel = candle.name ?? '';
          return [
            `<div style="font-weight:700;margin-bottom:6px;">${dateLabel}</div>`,
            `${t('chart.tooltip.open')}: ${open.toFixed(2)}`,
            `${t('chart.tooltip.close')}: ${close.toFixed(2)}`,
            `${t('chart.tooltip.high')}: ${high.toFixed(2)}`,
            `${t('chart.tooltip.low')}: ${low.toFixed(2)}`,
            volume ? `${t('chart.tooltip.volume')}: ${(volume.data as { value: number }).value.toLocaleString()}` : '',
            dif ? `DIF: ${Number(dif.data).toFixed(2)}` : '',
            dea ? `DEA: ${Number(dea.data).toFixed(2)}` : '',
            macdBar ? `MACD: ${Number((macdBar.data as { value: number | null }).value ?? 0).toFixed(2)}` : '',
            rsi6 ? `RSI6: ${Number(rsi6.data).toFixed(2)}` : '',
          ]
            .filter(Boolean)
            .join('<br/>');
        },
      },
      grid: [
        { left: 84, right: 24, top: 64, height: 330, containLabel: false },
        { left: 84, right: 24, top: 434, height: 86, containLabel: false },
        { left: 84, right: 24, top: 544, height: 86, containLabel: false },
        { left: 84, right: 24, top: 654, height: 86, containLabel: false },
      ],
      xAxis: [
        {
          type: 'category',
          data: dates,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: {
            color: '#8b949e',
            hideOverlap: true,
            margin: 10,
            fontSize: 11,
            formatter: (value: string) => value,
          },
          min: 'dataMin',
          max: 'dataMax',
        },
        {
          type: 'category',
          gridIndex: 1,
          data: dates,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e', hideOverlap: true, show: false },
          min: 'dataMin',
          max: 'dataMax',
        },
        {
          type: 'category',
          gridIndex: 2,
          data: dates,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e', hideOverlap: true, show: false },
          min: 'dataMin',
          max: 'dataMax',
        },
        {
          type: 'category',
          gridIndex: 3,
          data: dates,
          boundaryGap: false,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e', hideOverlap: true, show: false },
          min: 'dataMin',
          max: 'dataMax',
        },
      ],
      yAxis: [
        {
          scale: true,
          splitArea: { show: true, areaStyle: { color: ['rgba(255,255,255,0.01)', 'rgba(255,255,255,0.03)'] } },
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e' },
          splitLine: { lineStyle: { color: '#21262d' } },
        },
        {
          scale: true,
          gridIndex: 1,
          splitNumber: 2,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e', formatter: (value: number) => formatVolumeAxisLabel(value) },
          splitLine: { lineStyle: { color: '#21262d' } },
        },
        {
          scale: true,
          gridIndex: 2,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e' },
          splitLine: { lineStyle: { color: '#21262d' } },
        },
        {
          scale: true,
          gridIndex: 3,
          min: 0,
          max: 100,
          axisLine: { lineStyle: { color: '#30363d' } },
          axisLabel: { color: '#8b949e', margin: 8 },
          splitLine: { lineStyle: { color: '#21262d' } },
        },
      ],
      dataZoom: [
        {
          type: 'inside',
          xAxisIndex: [0, 1, 2, 3],
          start: 60,
          end: 100,
        },
        {
          type: 'slider',
          xAxisIndex: [0, 1, 2, 3],
          bottom: 4,
          height: 18,
          start: 60,
          end: 100,
          brushSelect: false,
          borderColor: '#30363d',
          fillerColor: 'rgba(37, 99, 235, 0.25)',
          handleStyle: { color: '#2563eb' },
          textStyle: { color: '#8b949e' },
        },
      ],
      series: [
        {
          name: legendName.kline,
          id: 'kline-main',
          type: 'candlestick',
          xAxisIndex: 0,
          yAxisIndex: 0,
          data: candleData,
          markPoint: markPointData.length > 0 ? {
            silent: true,
            data: markPointData,
          } : undefined,
          markArea: markAreaData.length > 0 ? {
            silent: true,
            itemStyle: {
              color: 'rgba(250, 204, 21, 0.12)',
            },
            data: markAreaData,
          } : undefined,
          itemStyle: {
            color: '#ef4444',
            color0: '#22c55e',
            borderColor: '#ef4444',
            borderColor0: '#22c55e',
            opacity: visibility.kline ? 1 : 0,
          },
        },
        {
          name: legendName.ma,
          id: 'ma5-line',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.ma && maConfig.ma5 ? ma.ma5List : ma.ma5List.map(() => null),
          lineStyle: { color: '#f5c842', width: 1.6 },
        },
        {
          name: legendName.ma,
          id: 'ma10-line',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.ma && maConfig.ma10 ? ma.ma10List : ma.ma10List.map(() => null),
          lineStyle: { color: '#4a90d9', width: 1.6 },
        },
        {
          name: legendName.ma,
          id: 'ma20-line',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.ma && maConfig.ma20 ? ma.ma20List : ma.ma20List.map(() => null),
          lineStyle: { color: '#9b59b6', width: 1.6 },
        },
        {
          name: legendName.ma,
          id: 'ma30-line',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.ma && maConfig.ma30 ? ma.ma30List : ma.ma30List.map(() => null),
          lineStyle: { color: '#2ecc71', width: 1.6 },
        },
        {
          name: legendName.ma,
          id: 'ma60-line',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.ma && maConfig.ma60 ? ma.ma60List : ma.ma60List.map(() => null),
          lineStyle: { color: '#f97316', width: 1.6 },
        },
        {
          name: legendName.boll,
          id: 'boll-upper',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.boll && bollConfig.upper ? boll.upperList : boll.upperList.map(() => null),
          lineStyle: { color: '#9ca3af', width: 1.3, type: 'dashed' },
          areaStyle: bollConfig.band ? { color: 'rgba(156, 163, 175, 0.06)' } : undefined,
        },
        {
          name: legendName.boll,
          id: 'boll-middle',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.boll && bollConfig.middle ? boll.middleList : boll.middleList.map(() => null),
          lineStyle: { color: '#9ca3af', width: 1.1, type: 'dashed' },
        },
        {
          name: legendName.boll,
          id: 'boll-lower',
          type: 'line',
          xAxisIndex: 0,
          yAxisIndex: 0,
          showSymbol: false,
          data: visibility.boll && bollConfig.lower ? boll.lowerList : boll.lowerList.map(() => null),
          lineStyle: { color: '#9ca3af', width: 1.3, type: 'dashed' },
        },
        {
          name: legendName.volume,
          id: 'volume-bar',
          type: 'bar',
          xAxisIndex: 1,
          yAxisIndex: 1,
          data: visibility.volume ? volumes : volumes.map(() => ({ value: null })),
          barWidth: '60%',
          itemStyle: { opacity: 0.9 },
        },
        {
          name: legendName.macd,
          id: 'dif-line',
          type: 'line',
          xAxisIndex: 2,
          yAxisIndex: 2,
          showSymbol: false,
          data: visibility.macd && macdConfig.dif ? macd.difList : macd.difList.map(() => null),
          lineStyle: { color: '#4a90d9', width: 1.4 },
        },
        {
          name: legendName.macd,
          id: 'dea-line',
          type: 'line',
          xAxisIndex: 2,
          yAxisIndex: 2,
          showSymbol: false,
          data: visibility.macd && macdConfig.dea ? macd.deaList : macd.deaList.map(() => null),
          lineStyle: { color: '#f5c842', width: 1.4 },
        },
        {
          name: legendName.macd,
          id: 'macd-bar',
          type: 'bar',
          xAxisIndex: 2,
          yAxisIndex: 2,
          data: visibility.macd && macdConfig.hist ? macdBars : macdBars.map(() => ({ value: null })),
          barWidth: '55%',
        },
        {
          name: legendName.rsi,
          id: 'rsi6-line',
          type: 'line',
          xAxisIndex: 3,
          yAxisIndex: 3,
          showSymbol: false,
          data: visibility.rsi ? rsi.rsi6List : rsi.rsi6List.map(() => null),
          lineStyle: { color: '#f5c842', width: 1.4 },
        },
      ],
    };
  }, [backtestTrades, bollConfig, indicators, klines, macdConfig, maConfig, symbol, t, visibility]);

  useEffect(() => {
    if (!chartRef.current) {
      return;
    }

    const chart = echarts.init(chartRef.current, undefined, { renderer: 'canvas' });
    chartInstanceRef.current = chart;
    chart.setOption(option, true);

    const handleResize = () => chart.resize();
    window.addEventListener('resize', handleResize);

    return () => {
      window.removeEventListener('resize', handleResize);
      chart.dispose();
      chartInstanceRef.current = null;
    };
  }, [option]);

  useEffect(() => {
    if (chartInstanceRef.current) {
      chartInstanceRef.current.setOption(option, true);
    }
  }, [option]);

  return <div ref={chartRef} className="chart-wrap w-100" />;
}
