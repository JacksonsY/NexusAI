import { computed, effectScope, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue';
import type { Ref } from 'vue';
import type { PaginationProps } from 'naive-ui';
import { useBoolean, useTable as useRawTable } from '@sa/hooks';
import type { PaginationData, TableColumnCheck, UseTableOptions } from '@sa/hooks';
import type { FlatResponseData } from '@sa/axios';
import { jsonClone } from '@sa/utils';
import { useAppStore } from '@/store/modules/app';
import { $t } from '@/locales';

export type UseNaiveTableOptions<ResponseData, ApiData, Pagination extends boolean> = Omit<
  UseTableOptions<ResponseData, ApiData, NaiveUI.TableColumn<ApiData>, Pagination>,
  'pagination' | 'getColumnChecks' | 'getColumns'
> & {
  /**
   * get column visible
   *
   * @param column
   *
   * @default true
   *
   * @returns true if the column is visible, false otherwise
   */
  getColumnVisible?: (column: NaiveUI.TableColumn<ApiData>) => boolean;
};

const SELECTION_KEY = '__selection__';

const EXPAND_KEY = '__expand__';

/**
 * Simplified useTable hook that accepts apiFn/apiParams style config.
 * Wraps the raw useTable from @sa/hooks with naive-ui column helpers and pagination.
 */
export function useTable<T = any>(config: {
  apiFn: (...args: any[]) => Promise<any>;
  apiParams: Record<string, any>;
  columns: () => any[];
  immediate?: boolean;
}) {
  const scope = effectScope();
  const appStore = useAppStore();

  const { apiFn, apiParams, immediate = true } = config;

  const searchParams = reactive<Record<string, any>>({ ...apiParams });

  const loading = ref(false);
  const empty = ref(false);
  const data = ref<T[]>([]) as Ref<T[]>;

  const columnChecks = ref(getColumnChecks(config.columns())) as Ref<TableColumnCheck[]>;
  const columns = computed(() => getColumns(config.columns(), columnChecks.value));

  const pagination = reactive({
    page: 1,
    pageSize: 10,
    itemCount: 0,
    showSizePicker: true,
    pageSizes: [10, 15, 20, 25, 30],
    onUpdatePage(page: number) {
      pagination.page = page;
    },
    onUpdatePageSize(pageSize: number) {
      pagination.pageSize = pageSize;
      pagination.page = 1;
    }
  }) as PaginationProps;

  const isMobile = computed(() => appStore.isMobile);

  const mobilePagination = computed(() => {
    const p: PaginationProps = {
      ...pagination,
      pageSlot: isMobile.value ? 3 : 9
    };
    return p;
  });

  function updateSearchParams(params: Record<string, any>) {
    Object.assign(searchParams, params);
  }

  function resetSearchParams() {
    Object.assign(searchParams, { ...apiParams });
    getData();
  }

  function reloadColumns() {
    const checkMap = new Map(columnChecks.value.map(col => [col.key, col.checked]));
    const defaultChecks = getColumnChecks(config.columns());
    columnChecks.value = defaultChecks.map(col => ({
      ...col,
      checked: checkMap.get(col.key) ?? col.checked
    }));
  }

  async function getData() {
    loading.value = true;
    console.log('[useTable] getData 调用，searchParams:', JSON.parse(JSON.stringify(searchParams)));
    try {
      const response = await apiFn(searchParams);

      // Handle flat response { data, error } or raw response
      const resData = response?.data ?? response;
      let records: T[] = [];
      let total = 0;

      if (Array.isArray(resData)) {
        records = resData;
        total = resData.length;
      } else if (resData && typeof resData === 'object') {
        records = resData.data ?? resData.records ?? [];
        total = resData.total ?? records.length;
      }

      const page = searchParams.page ?? 1;
      const size = searchParams.size ?? searchParams.pageSize ?? 10;

      data.value = records.map((item: any, index: number) => ({
        ...item,
        index: (page - 1) * size + index + 1
      }));

      empty.value = data.value.length === 0;

      pagination.itemCount = total;
      pagination.page = page;
      pagination.pageSize = size;
    } finally {
      loading.value = false;
    }
  }

  scope.run(() => {
    watch(
      () => appStore.locale,
      () => {
        reloadColumns();
      }
    );
  });

  onScopeDispose(() => {
    scope.stop();
  });

  if (immediate) {
    getData();
  }

  return {
    loading,
    empty,
    data,
    columns,
    columnChecks,
    reloadColumns,
    pagination,
    mobilePagination,
    getData,
    searchParams,
    updateSearchParams,
    resetSearchParams
  };
}

export function useNaiveTable<ResponseData, ApiData>(options: UseNaiveTableOptions<ResponseData, ApiData, false>) {
  const scope = effectScope();
  const appStore = useAppStore();

  const result = useRawTable<ResponseData, ApiData, NaiveUI.TableColumn<ApiData>, false>({
    ...options,
    getColumnChecks: cols => getColumnChecks(cols, options.getColumnVisible),
    getColumns
  });

  // calculate the total width of the table this is used for horizontal scrolling
  const scrollX = computed(() => {
    return result.columns.value.reduce((acc, column) => {
      return acc + Number(column.width ?? column.minWidth ?? 120);
    }, 0);
  });

  scope.run(() => {
    watch(
      () => appStore.locale,
      () => {
        result.reloadColumns();
      }
    );
  });

  onScopeDispose(() => {
    scope.stop();
  });

  return {
    ...result,
    scrollX
  };
}

type PaginationParams = Pick<PaginationProps, 'page' | 'pageSize'>;

type UseNaivePaginatedTableOptions<ResponseData, ApiData> = UseNaiveTableOptions<ResponseData, ApiData, true> & {
  paginationProps?: Omit<PaginationProps, 'page' | 'pageSize' | 'itemCount'>;
  /**
   * whether to show the total count of the table
   *
   * @default true
   */
  showTotal?: boolean;
  onPaginationParamsChange?: (params: PaginationParams) => void | Promise<void>;
};

export function useNaivePaginatedTable<ResponseData, ApiData>(
  options: UseNaivePaginatedTableOptions<ResponseData, ApiData>
) {
  const scope = effectScope();
  const appStore = useAppStore();

  const isMobile = computed(() => appStore.isMobile);

  const showTotal = computed(() => options.showTotal ?? true);

  const pagination = reactive({
    page: 1,
    pageSize: 10,
    itemCount: 0,
    showSizePicker: true,
    pageSizes: [10, 15, 20, 25, 30],
    prefix: showTotal.value ? page => $t('datatable.itemCount', { total: page.itemCount }) : undefined,
    onUpdatePage(page) {
      pagination.page = page;
    },
    onUpdatePageSize(pageSize) {
      pagination.pageSize = pageSize;
      pagination.page = 1;
    },
    ...options.paginationProps
  }) as PaginationProps;

  // this is for mobile, if the system does not support mobile, you can use `pagination` directly
  const mobilePagination = computed(() => {
    const p: PaginationProps = {
      ...pagination,
      pageSlot: isMobile.value ? 3 : 9,
      prefix: !isMobile.value && showTotal.value ? pagination.prefix : undefined
    };

    return p;
  });

  const paginationParams = computed(() => {
    const { page, pageSize } = pagination;

    return {
      page,
      pageSize
    };
  });

  const result = useRawTable<ResponseData, ApiData, NaiveUI.TableColumn<ApiData>, true>({
    ...options,
    pagination: true,
    getColumnChecks: cols => getColumnChecks(cols, options.getColumnVisible),
    getColumns,
    onFetched: data => {
      pagination.itemCount = data.total;
      pagination.pageSize = data.pageSize;
    }
  });

  async function getDataByPage(page: number = 1) {
    if (page !== pagination.page) {
      pagination.page = page;

      return;
    }

    await result.getData();
  }

  scope.run(() => {
    watch(
      () => appStore.locale,
      () => {
        result.reloadColumns();
      }
    );

    watch(paginationParams, async newVal => {
      await options.onPaginationParamsChange?.(newVal);

      await result.getData();
    });
  });

  onScopeDispose(() => {
    scope.stop();
  });

  return {
    ...result,
    getDataByPage,
    pagination,
    mobilePagination
  };
}

export function useTableOperate<TableData extends { id: any }>(data: Ref<TableData[]>, getData: () => Promise<void>) {
  const { bool: drawerVisible, setTrue: openDrawer, setFalse: closeDrawer } = useBoolean();

  const operateType = shallowRef<NaiveUI.TableOperateType>('add');

  function handleAdd() {
    operateType.value = 'add';
    openDrawer();
  }

  /** the editing row data */
  const editingData = shallowRef<TableData | null>(null);

  function handleEdit(id: TableData['id']) {
    operateType.value = 'edit';
    const findItem = data.value.find(item => item.id === id) || null;
    editingData.value = jsonClone(findItem);

    openDrawer();
  }

  /** the checked row keys of table */
  const checkedRowKeys = shallowRef<string[]>([]);

  /** the hook after the batch delete operation is completed */
  async function onBatchDeleted() {
    window.$message?.success($t('common.deleteSuccess'));

    checkedRowKeys.value = [];

    await getData();
  }

  /** the hook after the delete operation is completed */
  async function onDeleted() {
    window.$message?.success($t('common.deleteSuccess'));

    await getData();
  }

  return {
    drawerVisible,
    openDrawer,
    closeDrawer,
    operateType,
    handleAdd,
    editingData,
    handleEdit,
    checkedRowKeys,
    onBatchDeleted,
    onDeleted
  };
}

export function defaultTransform<ApiData>(
  response: FlatResponseData<any, Api.Common.PaginatingQueryRecord<ApiData>>
): PaginationData<ApiData> {
  const { data, error } = response;

  if (!error) {
    const { records, current, size, total } = data;

    return {
      data: records,
      pageNum: current,
      pageSize: size,
      total
    };
  }

  return {
    data: [],
    pageNum: 1,
    pageSize: 10,
    total: 0
  };
}

function getColumnChecks<Column extends NaiveUI.TableColumn<any>>(
  cols: Column[],
  getColumnVisible?: (column: Column) => boolean
) {
  const checks: TableColumnCheck[] = [];

  cols.forEach(column => {
    if (isTableColumnHasKey(column)) {
      checks.push({
        key: column.key as string,
        title: column.title!,
        checked: true,
        fixed: column.fixed ?? 'unFixed',
        visible: getColumnVisible?.(column) ?? true
      });
    } else if (column.type === 'selection') {
      checks.push({
        key: SELECTION_KEY,
        title: $t('common.check'),
        checked: true,
        fixed: column.fixed ?? 'unFixed',
        visible: getColumnVisible?.(column) ?? false
      });
    } else if (column.type === 'expand') {
      checks.push({
        key: EXPAND_KEY,
        title: $t('common.expandColumn'),
        checked: true,
        fixed: column.fixed ?? 'unFixed',
        visible: getColumnVisible?.(column) ?? false
      });
    }
  });

  return checks;
}

function getColumns<Column extends NaiveUI.TableColumn<any>>(cols: Column[], checks: TableColumnCheck[]) {
  const columnMap = new Map<string, Column>();

  cols.forEach(column => {
    if (isTableColumnHasKey(column)) {
      columnMap.set(column.key as string, column);
    } else if (column.type === 'selection') {
      columnMap.set(SELECTION_KEY, column);
    } else if (column.type === 'expand') {
      columnMap.set(EXPAND_KEY, column);
    }
  });

  const filteredColumns = checks
    .filter(item => item.checked)
    .map(check => {
      return {
        ...columnMap.get(check.key),
        fixed: check.fixed
      } as Column;
    });

  return filteredColumns;
}

export function isTableColumnHasKey<T>(column: NaiveUI.TableColumn<T>): column is NaiveUI.TableColumnWithKey<T> {
  return Boolean((column as NaiveUI.TableColumnWithKey<T>).key);
}
