<script setup lang="tsx">
import { computed, reactive, ref } from 'vue';
import { NButton, NDropdown, NTag } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';
import { useTable } from '@/hooks/common/table';
import {
  fetchAddAdminUser,
  fetchAdminUsers,
  fetchDeleteAdminUser,
  fetchProjects,
  fetchResetAdminUserPassword,
  fetchUpdateAdminUser
} from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({ name: 'AdminUsers' });

const appStore = useAppStore();

const ROLE_USER = 1;
const ROLE_ADMIN = 2;

const fmtDt = (s?: unknown) => (typeof s === 'string' && s ? s.replace('T', ' ').slice(0, 16) : '-');

// 项目码下拉（项目运维授权用·全量项目）
const projectOptions = ref<{ label: string; value: string }[]>([]);
async function loadProjects() {
  const { data, error } = await fetchProjects();
  if (!error && data) {
    projectOptions.value = data.map(p => ({ label: `${p.name}（${p.projectCode}）`, value: p.projectCode! }));
  }
}
loadProjects();

const { loading, data, columns, columnChecks, mobilePagination, getData } = useTable<NexusAI.AdminUser>({
  apiFn: fetchAdminUsers,
  apiParams: {},
  columns: () => [
    {
      title: '账号',
      key: 'username',
      width: 180,
      render: (r: NexusAI.AdminUser) => <span class="text-14px font-medium">{r.username}</span>
    },
    {
      title: '角色',
      key: 'role',
      width: 120,
      render: (r: NexusAI.AdminUser) =>
        r.role === ROLE_ADMIN ? (
          <NTag type="error" size="small" bordered={false}>
            超管
          </NTag>
        ) : (
          <NTag type="info" size="small" bordered={false}>
            项目运维
          </NTag>
        )
    },
    {
      title: '授权项目',
      key: 'projects',
      minWidth: 200,
      render: (r: NexusAI.AdminUser) => {
        if (r.role === ROLE_ADMIN) {
          return <span class="op-50">全部项目</span>;
        }
        const ps = r.projects ?? [];
        if (!ps.length) {
          return <span class="op-50">未授权任何项目</span>;
        }
        return (
          <div class="flex flex-wrap gap-4px py-2px">
            {ps.map(p => (
              <NTag key={p} size="small" bordered={false}>
                {p}
              </NTag>
            ))}
          </div>
        );
      }
    },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (r: NexusAI.AdminUser) => (
        <span class="flex items-center gap-6px">
          <span class="h-8px w-8px rounded-full" style={{ background: r.status === 1 ? '#18a058' : '#9ca3af' }} />
          <span class="text-13px">{r.status === 1 ? '启用' : '停用'}</span>
        </span>
      )
    },
    {
      title: '创建时间',
      key: 'createDt',
      width: 150,
      render: (r: NexusAI.AdminUser) => <span class="text-13px tabular-nums op-80">{fmtDt(r.createDt)}</span>
    },
    {
      title: '操作',
      key: 'actions',
      width: 70,
      align: 'center',
      render: (row: NexusAI.AdminUser) => (
        <NDropdown trigger="click" options={rowActions(row)} onSelect={(k: string) => onAction(k, row)}>
          <NButton size="tiny" quaternary aria-label="更多操作">
            <SvgIcon icon="lucide:ellipsis" class="text-16px" />
          </NButton>
        </NDropdown>
      )
    }
  ]
});

function rowProps(row: NexusAI.AdminUser) {
  return row.status === 1 ? {} : { style: 'opacity: 0.55' };
}

function rowActions(row: NexusAI.AdminUser) {
  const danger = { style: { color: '#d03050' } };
  const ic = (name: string) => () => <SvgIcon icon={name} class="text-14px" />;
  return [
    { label: '编辑', key: 'edit', icon: ic('lucide:pencil') },
    { label: '重置口令', key: 'password', icon: ic('lucide:key-round') },
    { type: 'divider' as const, key: 'd1' },
    row.status === 1
      ? { label: '停用', key: 'disable', icon: ic('lucide:user-x'), props: danger }
      : { label: '启用', key: 'enable', icon: ic('lucide:user-check') },
    { label: '删除', key: 'delete', icon: ic('lucide:trash-2'), props: danger }
  ];
}

// ===== 创建/编辑弹窗 =====
const formVisible = ref(false);
const editingId = ref<number | string | null>(null);
const form = reactive<{ username: string; password: string; role: number; status: number; projects: string[] }>({
  username: '',
  password: '',
  role: ROLE_USER,
  status: 1,
  projects: []
});
const isEdit = computed(() => editingId.value !== null);

function openCreate() {
  editingId.value = null;
  Object.assign(form, { username: '', password: '', role: ROLE_USER, status: 1, projects: [] });
  formVisible.value = true;
}

function openEdit(row: NexusAI.AdminUser) {
  editingId.value = row.id!;
  Object.assign(form, {
    username: row.username ?? '',
    password: '',
    role: row.role ?? ROLE_USER,
    status: row.status ?? 1,
    projects: [...(row.projects ?? [])]
  });
  formVisible.value = true;
}

async function submitForm() {
  if (!isEdit.value && (!form.username.trim() || !form.password)) {
    window.$message?.warning('账号与口令必填');
    return;
  }
  const payload: NexusAI.AdminUserUpsert = {
    role: form.role,
    status: form.status,
    projects: form.role === ROLE_USER ? form.projects : []
  };
  if (isEdit.value) {
    const { error } = await fetchUpdateAdminUser(editingId.value!, payload);
    if (!error) {
      window.$message?.success('已保存');
      formVisible.value = false;
      getData();
    }
    return;
  }
  payload.username = form.username.trim();
  payload.password = form.password;
  const { error } = await fetchAddAdminUser(payload);
  if (!error) {
    window.$message?.success('运维账号已创建');
    formVisible.value = false;
    getData();
  }
}

// ===== 重置口令弹窗 =====
const pwdVisible = ref(false);
const pwdTarget = ref<NexusAI.AdminUser | null>(null);
const newPassword = ref('');
function openResetPwd(row: NexusAI.AdminUser) {
  pwdTarget.value = row;
  newPassword.value = '';
  pwdVisible.value = true;
}
async function submitResetPwd() {
  if (!newPassword.value) {
    window.$message?.warning('请输入新口令');
    return;
  }
  const { error } = await fetchResetAdminUserPassword(pwdTarget.value!.id!, newPassword.value);
  if (!error) {
    window.$message?.success('口令已重置');
    pwdVisible.value = false;
  }
}

function confirmStatus(row: NexusAI.AdminUser, enable: boolean) {
  window.$dialog?.[enable ? 'info' : 'warning']({
    title: enable ? '启用账号' : '停用账号',
    content: `确定要${enable ? '启用' : '停用'}账号 "${row.username}" 吗？`,
    positiveText: enable ? '启用' : '停用',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await fetchUpdateAdminUser(row.id!, { status: enable ? 1 : 0 });
      if (!error) {
        window.$message?.success(enable ? '已启用' : '已停用');
        getData();
      }
    }
  });
}

function confirmDelete(row: NexusAI.AdminUser) {
  window.$dialog?.warning({
    title: '删除账号',
    content: `确定要删除运维账号 "${row.username}" 吗？该操作不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      const { error } = await fetchDeleteAdminUser(row.id!);
      if (!error) {
        window.$message?.success('已删除');
        getData();
      }
    }
  });
}

function onAction(key: string, row: NexusAI.AdminUser) {
  switch (key) {
    case 'edit':
      openEdit(row);
      break;
    case 'password':
      openResetPwd(row);
      break;
    case 'enable':
      confirmStatus(row, true);
      break;
    case 'disable':
      confirmStatus(row, false);
      break;
    case 'delete':
      confirmDelete(row);
      break;
    default:
  }
}

const roleOptions = [
  { label: '项目运维（仅授权项目的自助端点）', value: ROLE_USER },
  { label: '超级管理员（全部权限）', value: ROLE_ADMIN }
];
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="运维账号" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper">
      <template #header-extra>
        <TableHeaderOperation
          v-model:columns="columnChecks"
          :loading="loading"
          :show-batch-delete="false"
          @add="openCreate"
          @refresh="getData"
        />
      </template>
      <NDataTable
        :columns="columns"
        :data="data"
        :loading="loading"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="900"
        :row-key="r => r.id"
        :row-props="rowProps"
        :pagination="mobilePagination"
        class="sm:h-full"
      >
        <template #empty>
          <NEmpty description="还没有运维账号" class="py-48px">
            <template #extra>
              <NButton type="primary" size="small" @click="openCreate">
                <template #icon>
                  <SvgIcon icon="lucide:plus" />
                </template>
                新建运维账号
              </NButton>
            </template>
          </NEmpty>
        </template>
      </NDataTable>
    </NCard>

    <NModal v-model:show="formVisible" preset="card" :title="isEdit ? '编辑运维账号' : '新建运维账号'" class="w-480px">
      <NForm label-placement="top">
        <NFormItem v-if="!isEdit" label="账号" required>
          <NInput v-model:value="form.username" placeholder="登录账号" />
        </NFormItem>
        <NFormItem v-if="!isEdit" label="口令" required>
          <NInput v-model:value="form.password" type="password" show-password-on="click" placeholder="登录口令" />
        </NFormItem>
        <NFormItem label="角色">
          <NSelect v-model:value="form.role" :options="roleOptions" />
        </NFormItem>
        <NFormItem v-if="form.role === ROLE_USER" label="授权项目">
          <NSelect
            v-model:value="form.projects"
            multiple
            filterable
            :options="projectOptions"
            placeholder="选择该账号可管理的项目（= 命名空间授权）"
          />
        </NFormItem>
        <NFormItem label="状态">
          <NSwitch v-model:value="form.status" :checked-value="1" :unchecked-value="0">
            <template #checked>启用</template>
            <template #unchecked>停用</template>
          </NSwitch>
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="formVisible = false">取消</NButton>
          <NButton type="primary" @click="submitForm">保存</NButton>
        </div>
      </template>
    </NModal>

    <NModal v-model:show="pwdVisible" preset="card" title="重置口令" class="w-400px">
      <NForm label-placement="top">
        <NFormItem :label="`为账号「${pwdTarget?.username}」设置新口令`" required>
          <NInput v-model:value="newPassword" type="password" show-password-on="click" placeholder="新口令" />
        </NFormItem>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="pwdVisible = false">取消</NButton>
          <NButton type="primary" @click="submitResetPwd">重置</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>
