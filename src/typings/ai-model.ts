/**
 * AI Model Management Types
 * 模型管理系统的类型定义
 */

// ===== 枚举 =====

/**
 * 模型类型枚举
 */
export enum ModelType {
  CHAT = 'CHAT',
  EMBEDDING = 'EMBEDDING',
  RERANKER = 'RERANKER',
  IMAGE = 'IMAGE',
  SPEECH = 'SPEECH'
}

/**
 * 模型作用域枚举
 */
export enum ModelScope {
  GLOBAL = 'GLOBAL',
  PERSONAL = 'PERSONAL'
}

// ===== 基础接口 =====

/**
 * AI 模型提供商
 */
export interface AiModelProvider {
  id: number;
  providerName: string;
  providerKey: string;
  description: string;
  iconUrl: string;
  isEnabled: boolean;
  createdDt?: string;
  updatedDt?: string;
}

/**
 * AI 模型配置
 */
export interface AiModelConfig {
  id: number;
  providerId: number;
  providerName: string;
  modelName: string;
  modelKey: string;
  model?: string; // 模型标识，用于显示
  modelType: ModelType;
  description: string;
  apiEndpoint: string;
  configJson: Record<string, any>;
  scope: ModelScope;
  isDefault: boolean;
  isEnabled: boolean;
  ownerId?: number;
  createdDt: string;
  updatedDt: string;
}

/**
 * 模型使用统计
 */
export interface AiModelUsageStat {
  id: number;
  modelId: number;
  userId: number;
  totalCalls: number;
  successCalls: number;
  failedCalls: number;
  totalTokensUsed: number;
  avgResponseTime: number;
  lastUsedDt: string;
  createdDt?: string;
  updatedDt?: string;
}

// ===== 请求/响应类型 =====

/**
 * 创建提供商请求
 */
export interface CreateProviderRequest {
  providerName: string;
  providerKey: string;
  description: string;
  iconUrl?: string;
}

/**
 * 更新提供商请求
 */
export interface UpdateProviderRequest {
  id: number;
  providerName: string;
  description: string;
  iconUrl?: string;
}

/**
 * 创建模型配置请求
 */
export interface CreateModelRequest {
  providerId: number;
  modelName: string;
  modelKey: string;
  modelType: ModelType;
  description: string;
  apiKey: string;
  apiEndpoint: string;
  configJson: Record<string, any>;
}

/**
 * 更新模型配置请求
 */
export interface UpdateModelRequest extends CreateModelRequest {
  id: number;
}

/**
 * 分页结果
 */
export interface PageResult<T> {
  data: T[];
  pageNum: number;
  pageSize: number;
  total: number;
}

/**
 * API 错误响应
 */
export interface ApiError {
  code: string;
  message: string;
  details?: any;
}
