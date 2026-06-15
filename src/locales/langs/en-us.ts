const local: App.I18n.Schema = {
  system: {
    title: 'NexusAI',
    updateTitle: 'System Version Update Notification',
    updateContent: 'A new version of the system has been detected. Do you want to refresh the page immediately?',
    updateConfirm: 'Refresh immediately',
    updateCancel: 'Later'
  },
  common: {
    action: 'Action',
    add: 'Add',
    addSuccess: 'Add Success',
    backToHome: 'Back to home',
    batchDelete: 'Batch Delete',
    cancel: 'Cancel',
    close: 'Close',
    check: 'Check',
    selectAll: 'Select All',
    expandColumn: 'Expand Column',
    columnSetting: 'Column Setting',
    config: 'Config',
    confirm: 'Confirm',
    delete: 'Delete',
    deleteSuccess: 'Delete Success',
    confirmDelete: 'Are you sure you want to delete?',
    edit: 'Edit',
    warning: 'Warning',
    error: 'Error',
    index: 'Index',
    keywordSearch: 'Please enter keyword',
    logout: 'Logout',
    logoutConfirm: 'Are you sure you want to log out?',
    lookForward: 'Coming soon',
    modify: 'Modify',
    modifySuccess: 'Modify Success',
    noData: 'No Data',
    operate: 'Operate',
    pleaseCheckValue: 'Please check whether the value is valid',
    refresh: 'Refresh',
    reset: 'Reset',
    search: 'Search',
    switch: 'Switch',
    tip: 'Tip',
    trigger: 'Trigger',
    update: 'Update',
    updateSuccess: 'Update Success',
    userCenter: 'User Center',
    changePassword: 'Change Password',
    oldPassword: 'Old Password',
    newPassword: 'New Password',
    confirmNewPassword: 'Confirm New Password',
    oldPasswordPlaceholder: 'Please enter old password',
    newPasswordPlaceholder: 'Please enter new password',
    confirmNewPasswordPlaceholder: 'Please enter new password again',
    changePasswordSuccess: 'Password changed, please login again',
    yesOrNo: {
      yes: 'Yes',
      no: 'No'
    },
    save: 'Save',
    saveSuccess: 'Saved',
    createSuccess: 'Created successfully',
    createFailed: 'Create failed',
    saveFailed: 'Save failed',
    operateSuccess: 'Operation successful',
    operateFailed: 'Operation failed',
    ok: 'OK',
    name: 'Name',
    status: 'Status',
    type: 'Type',
    description: 'Description',
    version: 'Version',
    createTime: 'Created At',
    updateTime: 'Updated At',
    enable: 'Enable',
    disable: 'Disable',
    enabled: 'Enabled',
    disabled: 'Disabled',
    online: 'Online',
    offline: 'Offline',
    connected: 'Connected',
    disconnected: 'Disconnected',
    abnormal: 'Abnormal',
    detail: 'Detail',
    preview: 'Preview',
    download: 'Download',
    upload: 'Upload',
    publish: 'Publish',
    back: 'Back',
    setDefault: 'Set as Default',
    default: 'Default',
    loading: 'Loading...',
    none: 'None',
    pleaseInput: 'Please enter',
    pleaseSelect: 'Please select',
    loadMore: 'Load More',
    retry: 'Retry',
    rename: 'Rename',
    copy: 'Copy',
    copySuccess: 'Copied',
    today: 'Today',
    lastWeek: 'Last Week',
    currentMonth: 'Current Month',
    lastMonth: 'Last Month',
    lastTwoMonth: 'Last 2 Months',
    enabledSuccess: 'Enabled',
    disabledSuccess: 'Disabled'
  },
  request: {
    logout: 'Logout user after request failed',
    logoutMsg: 'User status is invalid, please log in again',
    logoutWithModal: 'Pop up modal after request failed and then log out user',
    logoutWithModalMsg: 'User status is invalid, please log in again',
    refreshToken: 'The requested token has expired, refresh the token',
    tokenExpired: 'The requested token has expired'
  },
  theme: {
    themeDrawerTitle: 'Theme Configuration',
    tabs: {
      appearance: 'Appearance',
      layout: 'Layout',
      general: 'General',
      preset: 'Preset'
    },
    appearance: {
      themeSchema: {
        title: 'Theme Schema',
        light: 'Light',
        dark: 'Dark',
        auto: 'Follow System'
      },
      grayscale: 'Grayscale',
      colourWeakness: 'Colour Weakness',
      themeColor: {
        title: 'Theme Color',
        primary: 'Primary',
        info: 'Info',
        success: 'Success',
        warning: 'Warning',
        error: 'Error',
        followPrimary: 'Follow Primary'
      },
      themeRadius: {
        title: 'Theme Radius'
      },
      recommendColor: 'Apply Recommended Color Algorithm',
      recommendColorDesc: 'The recommended color algorithm refers to',
      preset: {
        title: 'Theme Presets',
        apply: 'Apply',
        applySuccess: 'Preset applied successfully',
        default: {
          name: 'Default Preset',
          desc: 'Default theme preset with balanced settings'
        },
        dark: {
          name: 'Dark Preset',
          desc: 'Dark theme preset for night time usage'
        },
        compact: {
          name: 'Compact Preset',
          desc: 'Compact layout preset for small screens'
        },
        azir: {
          name: "Azir's Preset",
          desc: 'It is a cold and elegant preset that Azir likes'
        }
      }
    },
    layout: {
      layoutMode: {
        title: 'Layout Mode',
        vertical: 'Vertical Mode',
        horizontal: 'Horizontal Mode',
        'vertical-mix': 'Vertical Mix Mode',
        'vertical-hybrid-header-first': 'Left Hybrid Header-First',
        'top-hybrid-sidebar-first': 'Top-Hybrid Sidebar-First',
        'top-hybrid-header-first': 'Top-Hybrid Header-First',
        vertical_detail: 'Vertical menu layout, with the menu on the left and content on the right.',
        'vertical-mix_detail':
          'Vertical mix-menu layout, with the primary menu on the dark left side and the secondary menu on the lighter left side.',
        'vertical-hybrid-header-first_detail':
          'Left hybrid layout, with the primary menu at the top, the secondary menu on the dark left side, and the tertiary menu on the lighter left side.',
        horizontal_detail: 'Horizontal menu layout, with the menu at the top and content below.',
        'top-hybrid-sidebar-first_detail':
          'Top hybrid layout, with the primary menu on the left and the secondary menu at the top.',
        'top-hybrid-header-first_detail':
          'Top hybrid layout, with the primary menu at the top and the secondary menu on the left.'
      },
      tab: {
        title: 'Tab Settings',
        visible: 'Tab Visible',
        cache: 'Tag Bar Info Cache',
        cacheTip: 'One-click to open/close global keepalive',
        height: 'Tab Height',
        mode: {
          title: 'Tab Mode',
          slider: 'Slider',
          chrome: 'Chrome',
          button: 'Button'
        },
        closeByMiddleClick: 'Close Tab by Middle Click',
        closeByMiddleClickTip: 'Enable closing tabs by clicking with the middle mouse button'
      },
      header: {
        title: 'Header Settings',
        height: 'Header Height',
        breadcrumb: {
          visible: 'Breadcrumb Visible',
          showIcon: 'Breadcrumb Icon Visible'
        }
      },
      sider: {
        title: 'Sider Settings',
        inverted: 'Dark Sider',
        width: 'Sider Width',
        collapsedWidth: 'Sider Collapsed Width',
        mixWidth: 'Mix Sider Width',
        mixCollapsedWidth: 'Mix Sider Collapse Width',
        mixChildMenuWidth: 'Mix Child Menu Width',
        autoSelectFirstMenu: 'Auto Select First Submenu',
        autoSelectFirstMenuTip:
          'When a first-level menu is clicked, the first submenu is automatically selected and navigated to the deepest level'
      },
      footer: {
        title: 'Footer Settings',
        visible: 'Footer Visible',
        fixed: 'Fixed Footer',
        height: 'Footer Height',
        right: 'Right Footer'
      },
      content: {
        title: 'Content Area Settings',
        scrollMode: {
          title: 'Scroll Mode',
          tip: 'The theme scroll only scrolls the main part, the outer scroll can carry the header and footer together',
          wrapper: 'Wrapper',
          content: 'Content'
        },
        page: {
          animate: 'Page Animate',
          mode: {
            title: 'Page Animate Mode',
            fade: 'Fade',
            'fade-slide': 'Slide',
            'fade-bottom': 'Fade Zoom',
            'fade-scale': 'Fade Scale',
            'zoom-fade': 'Zoom Fade',
            'zoom-out': 'Zoom Out',
            none: 'None'
          }
        },
        fixedHeaderAndTab: 'Fixed Header And Tab'
      }
    },
    general: {
      title: 'General Settings',
      watermark: {
        title: 'Watermark Settings',
        visible: 'Watermark Full Screen Visible',
        text: 'Custom Watermark Text',
        enableUserName: 'Enable User Name Watermark',
        enableTime: 'Show Current Time',
        timeFormat: 'Time Format'
      },
      multilingual: {
        title: 'Multilingual Settings',
        visible: 'Display multilingual button'
      },
      globalSearch: {
        title: 'Global Search Settings',
        visible: 'Display GlobalSearch button'
      }
    },
    configOperation: {
      copyConfig: 'Copy Config',
      copySuccessMsg: 'Copy Success, Please replace the variable "themeSettings" in "src/theme/settings.ts"',
      resetConfig: 'Reset Config',
      resetSuccessMsg: 'Reset Success'
    }
  },
  route: {
    login: 'Login',
    403: 'No Permission',
    404: 'Page Not Found',
    500: 'Server Error',
    'iframe-page': 'Iframe',
    dashboard: 'Dashboard',
    projects: 'Projects',
    workspaces: 'Workspaces',
    health: 'Health',
    ops: 'Ops Monitor',
    performance: 'Performance',
    channels: 'Providers',
    models: 'Models',
    routing: 'Routing',
    'async-tasks': 'Async Tasks',
    usage: 'Usage Records',
    'cache-monitor': 'Cache',
    nodes: 'Cluster Nodes',
    'proxy-nodes': 'Proxy Nodes',
    modules: 'Modules',
    settings: 'Settings',
    'access-keys': 'Access Keys',
    protocols: 'Protocols',
    'key-sync': 'Key Sync',
    'email-settings': 'Email Settings',
    'notification-service': 'Notification Service',
    'ip-security': 'IP Security',
    'audit-logs': 'Audit Logs'
  },
  page: {
    login: {
      common: {
        loginOrRegister: 'Login / Register',
        userNamePlaceholder: 'Please enter user name',
        phonePlaceholder: 'Please enter phone number',
        codePlaceholder: 'Please enter verification code',
        passwordPlaceholder: 'Please enter password',
        confirmPasswordPlaceholder: 'Please enter password again',
        codeLogin: 'Verification code login',
        confirm: 'Confirm',
        back: 'Back',
        validateSuccess: 'Verification passed',
        loginSuccess: 'Login successfully',
        welcomeBack: 'Welcome back, {userName} !',
        login: 'Login',
        entryChat: 'Chat',
        entryAdmin: 'Admin Panel'
      },
      brand: {
        slogan: 'Any-to-any protocol conversion AI gateway',
        welcomeBack: 'Sign in to the console',
        feature1Title: 'Any-protocol conversion',
        feature1Desc: 'Normalize any inbound protocol → Canonical IR → generate any outbound protocol (N×N interconversion)',
        feature2Title: 'Two-level routing + channel keys',
        feature2Desc: 'Channel selection + 4-layer key pick (anchor/sticky/score/slot) + failover; USD hard gate',
        feature3Title: 'Channel=config · Protocol=code',
        feature3Desc: 'Protocol family as code (SPI), channel as config + rule marketplace (one-time stamp → contract④)'
      },
      pwdLogin: {
        title: 'Password Login',
        rememberMe: 'Remember me',
        forgetPassword: 'Forget password?',
        register: 'Register',
        otherAccountLogin: 'Other Account Login',
        otherLoginMode: 'Other Login Mode',
        superAdmin: 'Super Admin',
        admin: 'Admin',
        user: 'User'
      },
      codeLogin: {
        title: 'Verification Code Login',
        getCode: 'Get verification code',
        reGetCode: 'Reacquire after {time}s',
        sendCodeSuccess: 'Verification code sent successfully',
        imageCodePlaceholder: 'Please enter image verification code'
      },
      register: {
        title: 'Register',
        agreement: 'I have read and agree to',
        protocol: '《User Agreement》',
        policy: '《Privacy Policy》'
      },
      resetPwd: {
        title: 'Reset Password'
      },
      bindWeChat: {
        title: 'Bind WeChat'
      }
    },
    home: {
      operationalOverview: 'Operational overview',
      last7d: 'Last 7 days',
      last30d: 'Last 30 days',
      totalConversations: 'Total conversations',
      activeUsers: 'Active users',
      totalMessages: 'Total messages',
      totalToolCalls: 'Tool calls',
      avgResponseTime: 'Avg. response time',
      resourceOverview: 'Resources',
      agentRanking: 'Agent ranking',
      recentConversations: 'Recent conversations',
      viewAll: 'View all',
      rank: 'Rank',
      conversations: 'Conversations',
      messages: 'Messages',
      responseTime: 'Response time',
      skillCount: 'Skills',
      toolCallsTrend: 'Tool calls',
      metricsCards: 'Key metrics',
      yesterday: 'yesterday',
      memoryOverview: 'Memory Overview',
      knowledgeSummary: 'Knowledge Summary',
      skillsDashboard: 'Skills Dashboard',
      systemHealth: 'System Health',
      memoryTypeDistribution: 'Memory Type Distribution',
      resourceAllocation: 'Resource Allocation',
      totalMemories: 'Total Memories',
      totalKnowledgeBases: 'Total RAGs',
      totalDocuments: 'Total Documents',
      totalChunks: 'Total Chunks',
      totalSkills: 'Total Skills',
      retrievalEffectiveness: 'Retrieval Effectiveness',
      memoryType: 'Memory Type',
      agentActivity: 'Agent Activity',
      modelAvailability: 'Model Availability',
      knowledgeCoverage: 'Knowledge Coverage',
      memoryDepth: 'Memory Depth',
      skillDiversity: 'Skill Diversity',
      responseQuality: 'Response Quality',
      overallScore: 'Overall Score',
      associatedAgents: 'Associated Agents',
      tokenTrend: 'Token usage trend',
      tokenRanking: 'Model token usage',
      promptTokens: 'Prompt Token',
      completionTokens: 'Completion Token',
      tokenUsage: 'Token Usage',
      totalTokens: 'Total Tokens',
      totalCalls: 'Total Calls',
      totalCost: 'Total Cost',
      tokenColModel: 'Model',
      tokenColProvider: 'Provider',
      tokenColCalls: 'Calls',
      tokenColTokens: 'Tokens',
      tokenColCost: 'Cost',
      tokenColSuccessRate: 'Success rate',
      tokenColAvgResponse: 'Avg. response',
      branchDesc:
        'For the convenience of everyone in developing and updating the merge, we have streamlined the code of the main branch, only retaining the homepage menu, and the rest of the content has been moved to the example branch for maintenance. The preview address displays the content of the example branch.',
      greeting: 'Hello, {userName}',
      welcomeSubtitle: 'Welcome to NexusAI Gateway Console',
      overview: 'Overview',
      shortcuts: 'Shortcuts',
      agentCount: 'Agents',
      modelCount: 'Models',
      ragCount: 'Knowledge',
      mcpCount: 'MCP',
      goToAgent: 'Manage Agents',
      goToModel: 'Manage Models',
      goToRag: 'Manage RAG',
      goToMcp: 'Manage MCP',
      goToSkill: 'Skills',
      charts: 'Charts',
      usageTrend: 'Usage Trend',
      moduleDistribution: 'Module Distribution',
      conversationCount: 'Conversations',
      apiCallCount: 'API Calls',
      weatherDesc: 'Today is cloudy to clear, 20℃ - 25℃!',
      projectCount: 'Project Count',
      todo: 'Todo',
      message: 'Message',
      downloadCount: 'Download Count',
      registerCount: 'Register Count',
      schedule: 'Work and rest Schedule',
      study: 'Study',
      work: 'Work',
      rest: 'Rest',
      entertainment: 'Entertainment',
      visitCount: 'Visit Count',
      turnover: 'Turnover',
      dealCount: 'Deal Count',
      projectNews: {
        title: 'Project News',
        moreNews: 'More News',
        desc1: 'Soybean created the open source project soybean-admin on May 28, 2021!',
        desc2: 'Yanbowe submitted a bug to soybean-admin, the multi-tab bar will not adapt.',
        desc3: 'Soybean is ready to do sufficient preparation for the release of soybean-admin!',
        desc4: 'Soybean is busy writing project documentation for soybean-admin!',
        desc5: 'Soybean just wrote some of the workbench pages casually, and it was enough to see!'
      },
      creativity: 'Creativity'
    },
    model: {
      provider: {
        title: 'Model Providers',
        searchPlaceholder: 'Search provider name/key',
        clearFilter: 'Clear Filter',
        addProvider: 'Add Provider',
        noMatch: 'No matching providers',
        empty: 'No providers yet. Please add one.',
        detail: 'Provider Detail',
        addTitle: 'Add Provider',
        editTitle: 'Edit Provider',
        providerName: 'Provider Name',
        providerKey: 'Provider Key',
        icon: 'Icon',
        iconUrl: 'Icon URL',
        namePlaceholder: 'e.g. OpenAI',
        keyPlaceholder: 'e.g. openai',
        descriptionPlaceholder: 'Enter provider description',
        iconUrlPlaceholder: 'Enter logo icon URL',
        uploadOrSelectIcon: 'Upload / Select Icon',
        selectIcon: 'Select Provider Icon',
        iconHint: 'Click icon or button to upload/select provider logo',
        fillRequired: 'Please fill in required fields'
      },
      config: {
        title: 'Model Management',
        modelName: 'Model Name',
        modelKey: 'Model Key',
        defaultModel: 'Default Model',
        scope: 'Scope',
        apiEndpoint: 'API Endpoint',
        apiEndpointPlaceholder: 'Enter API endpoint URL (optional)',
        configParams: 'Configuration Parameters',
        setDefaultSuccess: 'Set successfully',
        confirmDelete: 'Confirm delete?',
        confirmDeleteModel: 'Are you sure you want to delete this model?',
        addTitle: 'Add Model Config',
        editTitle: 'Edit Model Config',
        detailTitle: 'Model Detail',
        selectProvider: 'Select provider',
        modelNamePlaceholder: 'e.g. gpt-4',
        modelKeyPlaceholder: 'e.g. gpt-4',
        selectType: 'Select type',
        descriptionPlaceholder: 'Enter model description',
        apiKeyPlaceholderNew: 'Enter API Key',
        apiKeyPlaceholderEdit: 'Leave empty to keep unchanged',
        apiKeyRequiredForNew: 'API Key is required when adding',
        fillRequired: 'Please fill in required fields',
        modelParams: 'Model Parameters (Optional)'
      },
      modelType: {
        chat: 'Chat Model',
        embedding: 'Embedding Model',
        reranker: 'Rerank Model',
        image: 'Image Model',
        speech: 'Speech Model'
      },
      modelScope: {
        global: 'Global Model',
        personal: 'Personal Model'
      },
      configPanel: {
        embeddingsPath: 'Embeddings Endpoint Path',
        embeddingsPathHint: 'API endpoint path for embedding models',
        embeddingDimension: 'Embedding Dimension',
        embeddingDimensionHint: 'Output vector dimension (e.g. 1536 for OpenAI)',
        timeout: 'Timeout (ms)',
        timeoutHint: 'API call timeout in milliseconds',
        completionsPath: 'Completions Endpoint Path',
        completionsPathHint: 'API endpoint path for chat models',
        temperatureHint: 'Controls output randomness (0=deterministic, 2=most random)',
        topPHint: 'Nucleus sampling parameter, controls output diversity (0=most conservative, 1=most open)',
        topKHint: 'Sample from top K most probable tokens',
        maxTokens: 'Max Tokens',
        maxTokensHint: 'Maximum tokens for model output',
        frequencyPenalty: 'Frequency Penalty',
        frequencyPenaltyHint: 'Reduce repetition frequency (negative=encourage, positive=penalize)',
        presencePenalty: 'Presence Penalty',
        presencePenaltyHint: 'Encourage new topics (negative=encourage new topics, positive=stick to existing topics)',
        rerankPath: 'Rerank Endpoint Path'
      },
      statistics: {
        title: 'Usage Statistics',
        totalCalls: 'Total Calls',
        successCalls: 'Successful Calls',
        failedCalls: 'Failed Calls',
        successRate: 'Success Rate',
        totalTokens: 'Total Token Usage',
        avgResponseTime: 'Avg Response Time',
        lastUsedTime: 'Last Used',
        noData: 'No statistics data'
      },
      errorCode: {
        configNotFound: 'Model config not found',
        configDisabled: 'Model config is disabled',
        accessDenied: 'You do not have permission to perform this action',
        invalidParameter: 'Invalid parameter',
        modelCallFailed: 'Model call failed',
        providerNotFound: 'Provider not found',
        duplicateProviderKey: 'Provider key already exists'
      },
      pagination: {
        total: 'Total {total} items'
      }
    },
    component: {
      resourcePicker: {
        title: 'Select Image',
        pleaseSelectImage: 'Please select an image file',
        uploadSuccess: 'Upload successful',
        uploadFailed: 'Upload failed',
        pleaseEnterUrl: 'Please enter image URL',
        localUpload: 'Local Upload',
        selectAndUpload: 'Select image to upload',
        uploadHint: 'Will be saved to resource library ({bizType}) and used as avatar URL',
        resourceLib: 'Resource Library',
        emptyHint: 'No resources, please upload first',
        imageUrl: 'Image URL',
        useThisUrl: 'Use This URL'
      },
      memory: {
        type: {
          fact: 'Fact',
          decision: 'Decision',
          preference: 'Preference',
          taskProgress: 'Task Progress',
          reference: 'Reference'
        },
        status: {
          active: 'Active',
          archived: 'Archived',
          suppressed: 'Suppressed'
        },
        date: {
          today: 'Today',
          yesterday: 'Yesterday',
          daysAgo: '{days} days ago',
          weeksAgo: '{weeks} weeks ago'
        },
        accessCount: 'Accessed {count} times',
        archive: 'Archive',
        suppress: 'Suppress',
        activate: 'Activate',
        confirmDelete: 'Are you sure you want to delete this memory?'
      },
      memoryEditor: {
        editTitle: 'Edit Memory',
        createTitle: 'New Memory',
        titleLabel: 'Title',
        titleRequired: 'Please enter title',
        titlePlaceholder: 'Enter memory title',
        contentLabel: 'Content',
        contentRequired: 'Please enter content',
        contentPlaceholder: 'Enter memory content',
        typeLabel: 'Memory Type',
        typeRequired: 'Please select memory type',
        typePlaceholder: 'Select memory type',
        categoryLabel: 'Category',
        categoryPlaceholder: 'Optional, e.g. Tech, Business',
        tagsLabel: 'Tags',
        relevanceLabel: 'Relevance Score',
        currentValue: 'Current:',
        statusPlaceholder: 'Select status',
        loadFailed: 'Failed to load memory',
        idNotFound: 'Memory ID not found'
      },
      memoryPanel: {
        allTypes: 'All Types',
        selectType: 'Select Type',
        searchPlaceholder: 'Search memories...',
        total: 'Total:',
        empty: 'No memories',
        refreshRetry: 'Refresh to retry',
        loadFailed: 'Failed to load memories',
        searchFailed: 'Search failed',
        archived: 'Archived',
        archiveFailed: 'Archive failed',
        suppressed: 'Suppressed',
        suppressFailed: 'Suppress failed',
        activated: 'Activated',
        activateFailed: 'Activate failed'
      },
      agentMemory: {
        all: 'All',
        searchPlaceholder: 'Search memories...',
        totalCount: '{count} memories',
        empty: 'No memories',
        emptyHint: 'Key memories will be auto-extracted after chatting with the agent',
        confirmDelete: 'Confirm delete this memory?',
        relevance: 'Relevance'
      }
    },
    systemUser: {
      roleNormal: 'Normal User',
      roleAdmin: 'Admin',
      columnRole: 'Role',
      authorize: 'Authorize',
      authorizeTitle: 'User Authorization',
      authorizeDaysPlaceholder: 'Enter authorization days',
      authorizeTotalsPlaceholder: 'Enter usage count'
    },
    homeExtra: {
      memoryTypeFact: 'Fact',
      memoryTypeDecision: 'Decision',
      memoryTypePreference: 'Preference',
      memoryTypeTaskProgress: 'Task Progress',
      memoryTypeReference: 'Reference',
      noMemoryData: 'No memory data',
      noSkillData: 'No skill data',
      noResourceData: 'No resource data',
      noKnowledgeData: 'No RAG data',
      noHealthData: 'No health data',
      topSkills: 'Top {count} Skills',
      topKnowledgeBases: 'Top {count} RAGs',
      statusActive: 'Active',
      statusProcessing: 'Processing',
      statusError: 'Error',
      legendKnowledgeBases: 'RAGs',
      legendSkills: 'Skills',
      legendMemories: 'Memories',
      legendConversations: 'Conversations',
      systemHealthName: 'System Health'
    }
  },
  form: {
    required: 'Cannot be empty',
    userName: {
      required: 'Please enter user name',
      invalid: 'User name format is incorrect'
    },
    phone: {
      required: 'Please enter phone number',
      invalid: 'Phone number format is incorrect'
    },
    pwd: {
      required: 'Please enter password',
      invalid: '6-18 characters, including letters, numbers, and underscores'
    },
    confirmPwd: {
      required: 'Please enter password again',
      invalid: 'The two passwords are inconsistent'
    },
    code: {
      required: 'Please enter verification code',
      invalid: 'Verification code format is incorrect'
    },
    email: {
      required: 'Please enter email',
      invalid: 'Email format is incorrect'
    }
  },
  dropdown: {
    closeCurrent: 'Close Current',
    closeOther: 'Close Other',
    closeLeft: 'Close Left',
    closeRight: 'Close Right',
    closeAll: 'Close All',
    pin: 'Pin Tab',
    unpin: 'Unpin Tab'
  },
  icon: {
    themeConfig: 'Theme Configuration',
    themeSchema: 'Theme Schema',
    lang: 'Switch Language',
    fullscreen: 'Fullscreen',
    fullscreenExit: 'Exit Fullscreen',
    reload: 'Reload Page',
    collapse: 'Collapse Menu',
    expand: 'Expand Menu',
    pin: 'Pin',
    unpin: 'Unpin'
  },
  datatable: {
    itemCount: 'Total {total} items',
    fixed: {
      left: 'Left Fixed',
      right: 'Right Fixed',
      unFixed: 'Unfixed'
    }
  }
};

export default local;
