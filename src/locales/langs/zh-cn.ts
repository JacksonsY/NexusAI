const local: App.I18n.Schema = {
  system: {
    title: 'NexusAI',
    updateTitle: '系统版本更新通知',
    updateContent: '检测到系统有新版本发布，是否立即刷新页面？',
    updateConfirm: '立即刷新',
    updateCancel: '稍后再说'
  },
  common: {
    action: '操作',
    add: '新增',
    addSuccess: '添加成功',
    backToHome: '返回首页',
    batchDelete: '批量删除',
    cancel: '取消',
    close: '关闭',
    check: '勾选',
    selectAll: '全选',
    expandColumn: '展开列',
    columnSetting: '列设置',
    config: '配置',
    confirm: '确认',
    delete: '删除',
    deleteSuccess: '删除成功',
    confirmDelete: '确认删除吗？',
    edit: '编辑',
    warning: '警告',
    error: '错误',
    index: '序号',
    keywordSearch: '请输入关键词搜索',
    logout: '退出登录',
    logoutConfirm: '确认退出登录吗？',
    lookForward: '敬请期待',
    modify: '修改',
    modifySuccess: '修改成功',
    noData: '无数据',
    operate: '操作',
    pleaseCheckValue: '请检查输入的值是否合法',
    refresh: '刷新',
    reset: '重置',
    search: '搜索',
    switch: '切换',
    tip: '提示',
    trigger: '触发',
    update: '更新',
    updateSuccess: '更新成功',
    userCenter: '个人中心',
    changePassword: '修改密码',
    oldPassword: '旧密码',
    newPassword: '新密码',
    confirmNewPassword: '确认新密码',
    oldPasswordPlaceholder: '请输入旧密码',
    newPasswordPlaceholder: '请输入新密码',
    confirmNewPasswordPlaceholder: '请再次输入新密码',
    changePasswordSuccess: '密码修改成功，请重新登录',
    yesOrNo: {
      yes: '是',
      no: '否'
    },
    save: '保存',
    saveSuccess: '已保存',
    createSuccess: '创建成功',
    createFailed: '创建失败',
    saveFailed: '保存失败',
    operateSuccess: '操作成功',
    operateFailed: '操作失败',
    ok: '确定',
    name: '名称',
    status: '状态',
    type: '类型',
    description: '描述',
    version: '版本',
    createTime: '创建时间',
    updateTime: '更新时间',
    enable: '启用',
    disable: '停用',
    enabled: '已启用',
    disabled: '已停用',
    online: '在线',
    offline: '离线',
    connected: '已连接',
    disconnected: '未连接',
    abnormal: '异常',
    detail: '详情',
    preview: '预览',
    download: '下载',
    upload: '上传',
    publish: '发布',
    back: '返回',
    setDefault: '设为默认',
    default: '默认',
    loading: '加载中...',
    none: '暂无',
    pleaseInput: '请输入',
    pleaseSelect: '请选择',
    loadMore: '加载更多',
    retry: '重试',
    rename: '重命名',
    copy: '复制',
    copySuccess: '复制成功',
    today: '今天',
    lastWeek: '最近一周',
    currentMonth: '当月',
    lastMonth: '最近一月',
    lastTwoMonth: '最近两月',
    enabledSuccess: '已启用',
    disabledSuccess: '已停用'
  },
  request: {
    logout: '请求失败后登出用户',
    logoutMsg: '用户状态失效，请重新登录',
    logoutWithModal: '请求失败后弹出模态框再登出用户',
    logoutWithModalMsg: '用户状态失效，请重新登录',
    refreshToken: '请求的token已过期，刷新token',
    tokenExpired: 'token已过期'
  },
  theme: {
    themeDrawerTitle: '主题配置',
    tabs: {
      appearance: '外观',
      layout: '布局',
      general: '通用',
      preset: '预设'
    },
    appearance: {
      themeSchema: {
        title: '主题模式',
        light: '亮色模式',
        dark: '暗黑模式',
        auto: '跟随系统'
      },
      grayscale: '灰色模式',
      colourWeakness: '色弱模式',
      themeColor: {
        title: '主题颜色',
        primary: '主色',
        info: '信息色',
        success: '成功色',
        warning: '警告色',
        error: '错误色',
        followPrimary: '跟随主色'
      },
      themeRadius: {
        title: '主题圆角'
      },
      recommendColor: '应用推荐算法的颜色',
      recommendColorDesc: '推荐颜色的算法参照',
      preset: {
        title: '主题预设',
        apply: '应用',
        applySuccess: '预设应用成功',
        default: {
          name: '默认预设',
          desc: 'Soybean 默认主题预设'
        },
        dark: {
          name: '暗色预设',
          desc: '适用于夜间使用的暗色主题预设'
        },
        compact: {
          name: '紧凑型',
          desc: '适用于小屏幕的紧凑布局预设'
        },
        azir: {
          name: 'Azir的预设',
          desc: '是 Azir 比较喜欢的莫兰迪色系冷淡风'
        }
      }
    },
    layout: {
      layoutMode: {
        title: '布局模式',
        vertical: '左侧菜单模式',
        'vertical-mix': '左侧菜单混合模式',
        'vertical-hybrid-header-first': '左侧混合-顶部优先',
        horizontal: '顶部菜单模式',
        'top-hybrid-sidebar-first': '顶部混合-侧边优先',
        'top-hybrid-header-first': '顶部混合-顶部优先',
        vertical_detail: '左侧菜单布局，菜单在左，内容在右。',
        'vertical-mix_detail': '左侧双菜单布局，一级菜单在左侧深色区域，二级菜单在左侧浅色区域。',
        'vertical-hybrid-header-first_detail':
          '左侧混合布局，一级菜单在顶部，二级菜单在左侧深色区域，三级菜单在左侧浅色区域。',
        horizontal_detail: '顶部菜单布局，菜单在顶部，内容在下方。',
        'top-hybrid-sidebar-first_detail': '顶部混合布局，一级菜单在左侧，二级菜单在顶部。',
        'top-hybrid-header-first_detail': '顶部混合布局，一级菜单在顶部，二级菜单在左侧。'
      },
      tab: {
        title: '标签栏设置',
        visible: '显示标签栏',
        cache: '标签栏信息缓存',
        cacheTip: '一键开启/关闭全局 keepalive',
        height: '标签栏高度',
        mode: {
          title: '标签栏风格',
          slider: '滑块风格',
          chrome: '谷歌风格',
          button: '按钮风格'
        },
        closeByMiddleClick: '鼠标中键关闭标签页',
        closeByMiddleClickTip: '启用后可以使用鼠标中键点击标签页进行关闭'
      },
      header: {
        title: '头部设置',
        height: '头部高度',
        breadcrumb: {
          visible: '显示面包屑',
          showIcon: '显示面包屑图标'
        }
      },
      sider: {
        title: '侧边栏设置',
        inverted: '深色侧边栏',
        width: '侧边栏宽度',
        collapsedWidth: '侧边栏折叠宽度',
        mixWidth: '混合布局侧边栏宽度',
        mixCollapsedWidth: '混合布局侧边栏折叠宽度',
        mixChildMenuWidth: '混合布局子菜单宽度',
        autoSelectFirstMenu: '自动选择第一个子菜单',
        autoSelectFirstMenuTip: '点击一级菜单时，自动选择并导航到第一个子菜单的最深层级'
      },
      footer: {
        title: '底部设置',
        visible: '显示底部',
        fixed: '固定底部',
        height: '底部高度',
        right: '底部居右'
      },
      content: {
        title: '内容区域设置',
        scrollMode: {
          title: '滚动模式',
          tip: '主题滚动仅 main 部分滚动，外层滚动可携带头部底部一起滚动',
          wrapper: '外层滚动',
          content: '主体滚动'
        },
        page: {
          animate: '页面切换动画',
          mode: {
            title: '页面切换动画类型',
            'fade-slide': '滑动',
            fade: '淡入淡出',
            'fade-bottom': '底部消退',
            'fade-scale': '缩放消退',
            'zoom-fade': '渐变',
            'zoom-out': '闪现',
            none: '无'
          }
        },
        fixedHeaderAndTab: '固定头部和标签栏'
      }
    },
    general: {
      title: '通用设置',
      watermark: {
        title: '水印设置',
        visible: '显示全屏水印',
        text: '自定义水印文本',
        enableUserName: '启用用户名水印',
        enableTime: '显示当前时间',
        timeFormat: '时间格式'
      },
      multilingual: {
        title: '多语言设置',
        visible: '显示多语言按钮'
      },
      globalSearch: {
        title: '全局搜索设置',
        visible: '显示全局搜索按钮'
      }
    },
    configOperation: {
      copyConfig: '复制配置',
      copySuccessMsg: '复制成功，请替换 src/theme/settings.ts 中的变量 themeSettings',
      resetConfig: '重置配置',
      resetSuccessMsg: '重置成功'
    }
  },
  route: {
    login: '登录',
    403: '无权限',
    404: '页面不存在',
    500: '服务器错误',
    'iframe-page': '外链页面',
    dashboard: '仪表盘',
    projects: '项目管理',
    workspaces: '工作区管理',
    health: '健康监控',
    ops: '运维监控',
    performance: '性能分析',
    channels: '提供商',
    models: '模型管理',
    routing: '调度策略',
    'async-tasks': '异步任务',
    usage: '使用记录',
    'cache-monitor': '缓存监控',
    nodes: '集群节点',
    'proxy-nodes': '代理节点',
    modules: '模块管理',
    settings: '系统设置',
    'access-keys': '访问密钥',
    protocols: '协议中心',
    'key-sync': 'Key 同步',
    'email-settings': '邮件配置',
    'notification-service': '通知服务',
    'ip-security': 'IP 安全',
    'audit-logs': '审计日志'
  },
  page: {
    login: {
      common: {
        loginOrRegister: '登录 / 注册',
        userNamePlaceholder: '请输入用户名',
        phonePlaceholder: '请输入手机号',
        codePlaceholder: '请输入验证码',
        passwordPlaceholder: '请输入密码',
        confirmPasswordPlaceholder: '请再次输入密码',
        codeLogin: '验证码登录',
        confirm: '确定',
        back: '返回',
        validateSuccess: '验证成功',
        loginSuccess: '登录成功',
        welcomeBack: '欢迎回来，{userName} ！',
        login: '登 录',
        entryChat: '对话页面',
        entryAdmin: '管理后台'
      },
      brand: {
        slogan: '任意协议互转网关 · 计费 / 路由 / 转发',
        welcomeBack: '请输入账号密码登录控制台',
        feature1Title: '任意协议互转',
        feature1Desc: '任意协议入站归一 → Canonical 中间表示 → 任意协议出站生成（N 协议 N×N 互转）',
        feature2Title: '两级路由 + 渠道密钥',
        feature2Desc: '渠道选择 + 四层选号（锚定/粘性/打分/并发槽）+ 故障转移；USD 强闸门精确计量',
        feature3Title: '渠道=配置·协议=代码',
        feature3Desc: '协议家族即代码（SPI），渠道差异用受控字段直编，快速接入百家供应商'
      },
      pwdLogin: {
        title: '密码登录',
        rememberMe: '记住我',
        forgetPassword: '忘记密码？',
        register: '注册账号',
        otherAccountLogin: '其他账号登录',
        otherLoginMode: '其他登录方式',
        superAdmin: '超级管理员',
        admin: '管理员',
        user: '普通用户'
      },
      codeLogin: {
        title: '验证码登录',
        getCode: '获取验证码',
        reGetCode: '{time}秒后重新获取',
        sendCodeSuccess: '验证码发送成功',
        imageCodePlaceholder: '请输入图片验证码'
      },
      register: {
        title: '注册账号',
        agreement: '我已经仔细阅读并接受',
        protocol: '《用户协议》',
        policy: '《隐私权政策》'
      },
      resetPwd: {
        title: '重置密码'
      },
      bindWeChat: {
        title: '绑定微信'
      }
    },
    home: {
      operationalOverview: '运营概览',
      last7d: '近 7 天',
      last30d: '近 30 天',
      totalConversations: '总对话',
      activeUsers: '活跃用户',
      totalMessages: '消息总量',
      totalToolCalls: '工具调用',
      avgResponseTime: '平均响应',
      resourceOverview: '资源概况',
      agentRanking: '智能体排行',
      recentConversations: '最近对话',
      viewAll: '查看全部',
      rank: '排名',
      conversations: '对话数',
      messages: '消息数',
      responseTime: '平均响应',
      skillCount: '技能',
      toolCallsTrend: '工具调用',
      metricsCards: '运营指标',
      yesterday: '昨天',
      memoryOverview: '记忆概览',
      knowledgeSummary: 'RAG摘要',
      skillsDashboard: '技能仪表板',
      systemHealth: '系统健康',
      memoryTypeDistribution: '记忆类型分布',
      resourceAllocation: '资源分配',
      totalMemories: '总记忆数',
      totalKnowledgeBases: 'RAG总数',
      totalDocuments: '文档总数',
      totalChunks: '分片总数',
      totalSkills: '技能总数',
      retrievalEffectiveness: '检索效果',
      memoryType: '记忆类型',
      agentActivity: '智能体活跃度',
      modelAvailability: '模型可用性',
      knowledgeCoverage: '知识覆盖度',
      memoryDepth: '记忆深度',
      skillDiversity: '技能多样性',
      responseQuality: '响应质量',
      overallScore: '综合评分',
      associatedAgents: '关联智能体',
      tokenTrend: 'Token 消耗趋势',
      tokenRanking: '模型 Token 消耗排行',
      promptTokens: 'Prompt Token',
      completionTokens: 'Completion Token',
      tokenUsage: 'Token 使用看板',
      totalTokens: 'Token 总消耗',
      totalCalls: '总调用次数',
      totalCost: '总费用',
      tokenColModel: '模型名称',
      tokenColProvider: '提供商',
      tokenColCalls: '调用次数',
      tokenColTokens: 'Token 消耗',
      tokenColCost: '费用',
      tokenColSuccessRate: '成功率',
      tokenColAvgResponse: '平均响应',
      branchDesc:
        '为了方便大家开发和更新合并，我们对main分支的代码进行了精简，只保留了首页菜单，其余内容已移至example分支进行维护。预览地址显示的内容即为example分支的内容。',
      greeting: '你好，{userName}',
      welcomeSubtitle: '欢迎使用 NexusAI 网关控制台',
      overview: '数据概览',
      shortcuts: '快捷入口',
      agentCount: '智能体',
      modelCount: '模型',
      ragCount: 'RAG',
      mcpCount: 'MCP 服务',
      goToAgent: '管理智能体',
      goToModel: '管理模型',
      goToRag: '管理RAG',
      goToMcp: '管理 MCP',
      goToSkill: '技能管理',
      charts: '数据图表',
      usageTrend: '调用趋势',
      moduleDistribution: '模块分布',
      conversationCount: '对话次数',
      apiCallCount: 'API 调用',
      weatherDesc: '今日多云转晴，20℃ - 25℃!',
      projectCount: '项目数',
      todo: '待办',
      message: '消息',
      downloadCount: '下载量',
      registerCount: '注册量',
      schedule: '作息安排',
      study: '学习',
      work: '工作',
      rest: '休息',
      entertainment: '娱乐',
      visitCount: '访问量',
      turnover: '成交额',
      dealCount: '成交量',
      projectNews: {
        title: '项目动态',
        moreNews: '更多动态',
        desc1: 'Soybean 在2021年5月28日创建了开源项目 soybean-admin!',
        desc2: 'Yanbowe 向 soybean-admin 提交了一个bug，多标签栏不会自适应。',
        desc3: 'Soybean 准备为 soybean-admin 的发布做充分的准备工作!',
        desc4: 'Soybean 正在忙于为soybean-admin写项目说明文档！',
        desc5: 'Soybean 刚才把工作台页面随便写了一些，凑合能看了！'
      },
      creativity: '创意'
    },
    model: {
      provider: {
        title: '模型提供商',
        searchPlaceholder: '搜索提供商名称/Key',
        clearFilter: '清除筛选',
        addProvider: '新增提供商',
        noMatch: '无匹配的提供商',
        empty: '暂无提供商，请先新增',
        detail: '提供商详情',
        addTitle: '新增提供商',
        editTitle: '编辑提供商',
        providerName: '提供商名称',
        providerKey: '提供商Key',
        icon: '图标',
        iconUrl: '图标URL',
        namePlaceholder: '例如: OpenAI',
        keyPlaceholder: '例如: openai',
        descriptionPlaceholder: '请输入提供商描述',
        iconUrlPlaceholder: '请输入LOGO图标URL',
        uploadOrSelectIcon: '上传 / 选择图标',
        selectIcon: '选择提供商图标',
        iconHint: '点击图标或按钮上传/选择提供商LOGO',
        fillRequired: '请填写必填项'
      },
      config: {
        title: '模型管理',
        modelName: '模型名称',
        modelKey: '模型Key',
        defaultModel: '默认模型',
        scope: '作用域',
        apiEndpoint: 'API端点',
        apiEndpointPlaceholder: '请输入API端点URL（选填）',
        configParams: '配置参数',
        setDefaultSuccess: '设置成功',
        confirmDelete: '确定删除？',
        confirmDeleteModel: '确定删除该模型吗？',
        addTitle: '新增模型配置',
        editTitle: '编辑模型配置',
        detailTitle: '模型详情',
        selectProvider: '请选择提供商',
        modelNamePlaceholder: '例如: gpt-4',
        modelKeyPlaceholder: '例如: gpt-4',
        selectType: '请选择类型',
        descriptionPlaceholder: '请输入模型描述',
        apiKeyPlaceholderNew: '请输入API Key',
        apiKeyPlaceholderEdit: '留空则不修改',
        apiKeyRequiredForNew: '新增时请填写 API Key',
        fillRequired: '请填写必填项',
        modelParams: '模型参数（可选）'
      },
      modelType: {
        chat: '对话模型',
        embedding: '向量模型',
        reranker: '重排模型',
        image: '图像模型',
        speech: '语音模型'
      },
      modelScope: {
        global: '全局模型',
        personal: '个人模型'
      },
      configPanel: {
        embeddingsPath: '嵌入端点路径',
        embeddingsPathHint: '向量模型的API端点路径',
        embeddingDimension: '嵌入维度',
        embeddingDimensionHint: '输出向量的维度（如OpenAI为1536）',
        timeout: '超时时间(ms)',
        timeoutHint: 'API调用的超时时间（毫秒）',
        completionsPath: '完成端点路径',
        completionsPathHint: '聊天模型的API端点路径',
        temperatureHint: '控制输出的随机性（0=确定，2=最随机）',
        topPHint: '核采样参数，控制输出多样性（0=最保守，1=最开放）',
        topKHint: '从概率最高的K个token中采样',
        maxTokens: '最大Token数',
        maxTokensHint: '模型输出的最大token数',
        frequencyPenalty: '频率惩罚',
        frequencyPenaltyHint: '降低重复词汇的出现频率（负数=鼓励，正数=惩罚）',
        presencePenalty: '存在惩罚',
        presencePenaltyHint: '鼓励模型讨论新话题（负数=鼓励新话题，正数=坚持已有话题）',
        rerankPath: '重排端点路径'
      },
      statistics: {
        title: '使用统计',
        totalCalls: '总调用次数',
        successCalls: '成功调用',
        failedCalls: '失败调用',
        successRate: '成功率',
        totalTokens: 'Token总用量',
        avgResponseTime: '平均响应时间',
        lastUsedTime: '最后使用时间',
        noData: '暂无统计数据'
      },
      errorCode: {
        configNotFound: '模型配置不存在',
        configDisabled: '模型配置已禁用',
        accessDenied: '您没有权限执行此操作',
        invalidParameter: '参数非法',
        modelCallFailed: '模型调用失败',
        providerNotFound: '提供商不存在',
        duplicateProviderKey: '提供商Key已存在'
      },
      pagination: {
        total: '共 {total} 条'
      }
    },
    component: {
      resourcePicker: {
        title: '选择图片',
        pleaseSelectImage: '请选择图片文件',
        uploadSuccess: '上传成功',
        uploadFailed: '上传失败',
        pleaseEnterUrl: '请输入图片地址',
        localUpload: '本地上传',
        selectAndUpload: '选择图片上传',
        uploadHint: '将写入资源库（{bizType}），并作为当前头像地址',
        resourceLib: '资源库',
        emptyHint: '暂无资源，请先上传',
        imageUrl: '图片链接',
        useThisUrl: '使用此链接'
      },
      memory: {
        type: {
          fact: '事实',
          decision: '决策',
          preference: '偏好',
          taskProgress: '任务进度',
          reference: '参考资料'
        },
        status: {
          active: '激活',
          archived: '已归档',
          suppressed: '已压制'
        },
        date: {
          today: '今天',
          yesterday: '昨天',
          daysAgo: '{days}天前',
          weeksAgo: '{weeks}周前'
        },
        accessCount: '访问 {count} 次',
        archive: '归档',
        suppress: '压制',
        activate: '激活',
        confirmDelete: '确定要删除这条记忆吗？'
      },
      memoryEditor: {
        editTitle: '编辑记忆',
        createTitle: '新建记忆',
        titleLabel: '标题',
        titleRequired: '请输入标题',
        titlePlaceholder: '请输入记忆标题',
        contentLabel: '内容',
        contentRequired: '请输入内容',
        contentPlaceholder: '请输入记忆内容',
        typeLabel: '记忆类型',
        typeRequired: '请选择记忆类型',
        typePlaceholder: '选择记忆类型',
        categoryLabel: '分类',
        categoryPlaceholder: '可选，例如：技术、业务',
        tagsLabel: '标签',
        relevanceLabel: '相关度评分',
        currentValue: '当前值:',
        statusPlaceholder: '选择状态',
        loadFailed: '加载记忆失败',
        idNotFound: '记忆ID不存在'
      },
      memoryPanel: {
        allTypes: '全部类型',
        selectType: '选择类型',
        searchPlaceholder: '搜索记忆...',
        total: '总计:',
        empty: '暂无记忆',
        refreshRetry: '刷新试试',
        loadFailed: '加载记忆失败',
        searchFailed: '搜索失败',
        archived: '已归档',
        archiveFailed: '归档失败',
        suppressed: '已压制',
        suppressFailed: '压制失败',
        activated: '已激活',
        activateFailed: '激活失败'
      },
      agentMemory: {
        all: '全部',
        searchPlaceholder: '搜索记忆...',
        totalCount: '共 {count} 条记忆',
        empty: '暂无记忆',
        emptyHint: '与智能体对话后将自动提取关键记忆',
        confirmDelete: '确认删除这条记忆？',
        relevance: '相关度'
      }
    },
    systemUser: {
      roleNormal: '普通用户',
      roleAdmin: '管理员',
      columnRole: '角色',
      authorize: '授权',
      authorizeTitle: '用户授权',
      authorizeDaysPlaceholder: '请输入授权天数',
      authorizeTotalsPlaceholder: '请输入使用次数'
    },
    homeExtra: {
      memoryTypeFact: '事实',
      memoryTypeDecision: '决策',
      memoryTypePreference: '偏好',
      memoryTypeTaskProgress: '任务进度',
      memoryTypeReference: '参考',
      noMemoryData: '暂无记忆数据',
      noSkillData: '暂无技能数据',
      noResourceData: '暂无资源数据',
      noKnowledgeData: '暂无RAG数据',
      noHealthData: '暂无健康数据',
      topSkills: 'Top {count} 技能',
      topKnowledgeBases: 'Top {count} RAG',
      statusActive: '活跃',
      statusProcessing: '处理中',
      statusError: '错误',
      legendKnowledgeBases: 'RAG',
      legendSkills: '技能',
      legendMemories: '记忆',
      legendConversations: '对话',
      systemHealthName: '系统健康'
    }
  },
  form: {
    required: '不能为空',
    userName: {
      required: '请输入用户名',
      invalid: '用户名格式不正确'
    },
    phone: {
      required: '请输入手机号',
      invalid: '手机号格式不正确'
    },
    pwd: {
      required: '请输入密码',
      invalid: '密码格式不正确，6-18位字符，包含字母、数字、下划线'
    },
    confirmPwd: {
      required: '请输入确认密码',
      invalid: '两次输入密码不一致'
    },
    code: {
      required: '请输入验证码',
      invalid: '验证码格式不正确'
    },
    email: {
      required: '请输入邮箱',
      invalid: '邮箱格式不正确'
    }
  },
  dropdown: {
    closeCurrent: '关闭',
    closeOther: '关闭其它',
    closeLeft: '关闭左侧',
    closeRight: '关闭右侧',
    closeAll: '关闭所有',
    pin: '固定标签',
    unpin: '取消固定'
  },
  icon: {
    themeConfig: '主题配置',
    themeSchema: '主题模式',
    lang: '切换语言',
    fullscreen: '全屏',
    fullscreenExit: '退出全屏',
    reload: '刷新页面',
    collapse: '折叠菜单',
    expand: '展开菜单',
    pin: '固定',
    unpin: '取消固定'
  },
  datatable: {
    itemCount: '共 {total} 条',
    fixed: {
      left: '左固定',
      right: '右固定',
      unFixed: '取消固定'
    }
  }
};

export default local;
