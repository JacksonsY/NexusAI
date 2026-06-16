import dayjs from 'dayjs';
import { $t } from '@/locales';

/**
 * Transform record to option
 *
 * @example
 * ```ts
 * const record = {
 * key1: 'label1',
 * key2: 'label2'
 * };
 * const options = transformRecordToOption(record);
 * // [
 * // { value: 'key1', label: 'label1' },
 * // { value: 'key2', label: 'label2' }
 * // ]
 * ```;
 *
 * @param record
 */
export function transformRecordToOption<T extends Record<string, string>>(record: T) {
  return Object.entries(record).map(([value, label]) => ({
    value,
    label
  })) as CommonType.Option<keyof T, T[keyof T]>[];
}

/**
 * Translate options
 *
 * @param options
 */
export function translateOptions(options: CommonType.Option<string, App.I18n.I18nKey>[]) {
  return options.map(option => ({
    ...option,
    label: $t(option.label)
  }));
}

/**
 * Toggle html class
 *
 * @param className
 */
export function toggleHtmlClass(className: string) {
  function add() {
    document.documentElement.classList.add(className);
  }

  function remove() {
    document.documentElement.classList.remove(className);
  }

  return {
    add,
    remove
  };
}

/** 最近 N 天的时间戳区间 */
export function dayRange(days: number = 1): [number, number] {
  return [
    dayjs()
      .subtract(days - 1, 'day')
      .startOf('day')
      .valueOf(),
    dayjs().endOf('day').valueOf()
  ];
}

/** 最近 N 个月的时间戳区间 */
export function monthRange(months: number = 1, startOf: dayjs.OpUnitType = 'day'): [number, number] {
  return [dayjs().subtract(months, 'month').startOf(startOf).valueOf(), dayjs().endOf('day').valueOf()];
}
