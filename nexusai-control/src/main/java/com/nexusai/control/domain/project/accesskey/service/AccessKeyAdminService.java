package com.nexusai.control.domain.project.accesskey.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexusai.billing.quota.QuotaLedger;
import com.nexusai.common.constant.GatewayConstants;
import com.nexusai.common.event.ConfigChangedEvent;
import com.nexusai.billing.money.UsdAmount;
import com.nexusai.common.exception.GatewayErrorCode;
import com.nexusai.common.exception.GatewayException;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyCreateRequest;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyCreateResponse;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyListItem;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyRotateResponse;
import com.nexusai.control.domain.project.accesskey.dto.AccessKeyUpdateRequest;
import com.nexusai.persistence.mapper.NaiProjectMapper;
import com.nexusai.persistence.mapper.NaiTokenMapper;
import com.nexusai.persistence.mapper.NaiWorkspaceMapper;
import com.nexusai.persistence.po.NaiProject;
import com.nexusai.persistence.po.NaiToken;
import com.nexusai.persistence.po.NaiWorkspace;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下游 Access Key 管理域。外部业务面同步的 Key 仍走 Key 同步端点；本服务只允许控制台创建/修改 {@code standalone=1} 的独立 Key。
 *
 * @author nexusai
 */
@Service
public class AccessKeyAdminService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GENERATED_KEY_BYTES = 32;
    private static final int MAX_GENERATE_ATTEMPTS = 5;

    private final NaiTokenMapper tokenMapper;
    private final NaiProjectMapper projectMapper;
    private final NaiWorkspaceMapper workspaceMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RedissonClient redisson;
    private final QuotaLedger quotaLedger;

    public AccessKeyAdminService(NaiTokenMapper tokenMapper,
                             NaiProjectMapper projectMapper,
                             NaiWorkspaceMapper workspaceMapper,
                             ApplicationEventPublisher eventPublisher,
                             RedissonClient redisson,
                             QuotaLedger quotaLedger) {
        this.tokenMapper = tokenMapper;
        this.projectMapper = projectMapper;
        this.workspaceMapper = workspaceMapper;
        this.eventPublisher = eventPublisher;
        this.redisson = redisson;
        this.quotaLedger = quotaLedger;
    }

    public PageResult<AccessKeyListItem> list(long pageNum, long pageSize, String projectCode, Boolean standalone) {
        PageQuery pq = PageQuery.of(pageNum, pageSize);
        LambdaQueryWrapper<NaiToken> wrapper = new LambdaQueryWrapper<NaiToken>()
                .eq(StringUtils.hasText(projectCode), NaiToken::getProjectCode, projectCode)
                .orderByDesc(NaiToken::getId);
        if (Boolean.TRUE.equals(standalone)) {
            wrapper.eq(NaiToken::getStandalone, 1);
        } else if (Boolean.FALSE.equals(standalone)) {
            wrapper.and(q -> q.eq(NaiToken::getStandalone, 0).or().isNull(NaiToken::getStandalone));
        }
        Page<NaiToken> page = tokenMapper.selectPage(
                Page.of(pq.getPageNum(), pq.getPageSize()),
                wrapper);
        // 安全：PO 投影为 VO，绝不外泄 keyHash 全量 / byokCredential 密文
        List<AccessKeyListItem> items = page.getRecords().stream().map(AccessKeyListItem::from).toList();
        return PageResult.of(items, page.getTotal(), pq.getPageNum(), pq.getPageSize());
    }

    public AccessKeyCreateResponse create(AccessKeyCreateRequest request) {
        String projectCode = requireText(request.projectCode(), "projectCode 必填");
        requireActiveProject(projectCode);
        validateWorkspace(projectCode, request.workspaceId());

        GeneratedKey generated = generateUniqueKey();
        NaiToken token = new NaiToken();
        token.setKeyHash(generated.hash());
        token.setName(normalizeName(request.name()));
        token.setStandalone(1);
        token.setProjectCode(projectCode);
        token.setWorkspaceId(request.workspaceId());
        token.setGroupCode(normalizeGroup(request.groupCode()));
        token.setModelLimits(blankToNull(request.modelLimits()));
        token.setAllowIps(blankToNull(request.allowIps()));
        token.setAllowProviders(blankToNull(request.allowProviders()));
        token.setAllowApiFormats(blankToNull(request.allowApiFormats()));
        token.setQuotaUsd(normalizeNullablePositiveUsd(request.quotaUsd()));
        token.setRpmLimit(normalizeNonNegative(request.rpmLimit()));
        token.setTpmLimit(normalizeNonNegative(request.tpmLimit()));
        token.setConcurrentLimit(normalizeNonNegative(request.concurrentLimit()));
        token.setExpiredTime(normalizeNonNegativeLong(request.expiredTime()));
        token.setAutoDeleteOnExpiry(normalizeBoolFlag(request.autoDeleteOnExpiry()));
        token.setFeatureSettings(blankToNull(request.featureSettings()));
        token.setStatus(1);
        tokenMapper.insert(token);
        broadcastTokenChanged(token.getId());
        return new AccessKeyCreateResponse(token.getId(), generated.plain(), token.getName());
    }

    public void update(Long id, AccessKeyUpdateRequest request) {
        NaiToken existing = requireStandalone(id);
        String nextProjectCode = StringUtils.hasText(request.projectCode()) ? request.projectCode().trim() : existing.getProjectCode();
        requireActiveProject(nextProjectCode);
        Long nextWorkspaceId = request.workspaceId();
        validateWorkspace(nextProjectCode, nextWorkspaceId);
        tokenMapper.update(null, new LambdaUpdateWrapper<NaiToken>()
                .eq(NaiToken::getId, id)
                .set(NaiToken::getName, normalizeName(request.name()))
                .set(NaiToken::getProjectCode, nextProjectCode)
                .set(NaiToken::getWorkspaceId, nextWorkspaceId)
                .set(NaiToken::getGroupCode, normalizeGroup(request.groupCode()))
                .set(NaiToken::getModelLimits, blankToNull(request.modelLimits()))
                .set(NaiToken::getAllowIps, blankToNull(request.allowIps()))
                .set(NaiToken::getAllowProviders, blankToNull(request.allowProviders()))
                .set(NaiToken::getAllowApiFormats, blankToNull(request.allowApiFormats()))
                .set(NaiToken::getQuotaUsd, normalizeNullablePositiveUsd(request.quotaUsd()))
                .set(NaiToken::getRpmLimit, normalizeNonNegative(request.rpmLimit()))
                .set(NaiToken::getTpmLimit, normalizeNonNegative(request.tpmLimit()))
                .set(NaiToken::getConcurrentLimit, normalizeNonNegative(request.concurrentLimit()))
                .set(NaiToken::getExpiredTime, normalizeNonNegativeLong(request.expiredTime()))
                .set(NaiToken::getAutoDeleteOnExpiry, normalizeBoolFlag(request.autoDeleteOnExpiry()))
                .set(NaiToken::getFeatureSettings, blankToNull(request.featureSettings()))
                .set(NaiToken::getStatus, normalizeStatus(request.status()))
                .set(NaiToken::getStandalone, 1));

        invalidateQuota(existing);
        if (!nextProjectCode.equals(existing.getProjectCode())) {
            quotaLedger.invalidateToken(nextProjectCode, id);
        }
        broadcastTokenChanged(id);
    }

    public AccessKeyRotateResponse rotate(Long id) {
        NaiToken existing = requireStandalone(id);
        GeneratedKey generated = generateUniqueKey();
        NaiToken patch = new NaiToken();
        patch.setId(id);
        patch.setKeyHash(generated.hash());
        tokenMapper.updateById(patch);
        invalidateQuota(existing);
        broadcastTokenChanged(id);
        return new AccessKeyRotateResponse(id, generated.plain());
    }

    public void setStatus(Long id, Integer status) {
        NaiToken existing = requireStandalone(id);
        NaiToken patch = new NaiToken();
        patch.setId(id);
        patch.setStatus(normalizeStatus(status));
        tokenMapper.updateById(patch);
        invalidateQuota(existing);
        broadcastTokenChanged(id);
    }

    public void delete(Long id) {
        NaiToken existing = requireStandalone(id);
        tokenMapper.deleteById(id);
        invalidateQuota(existing);
        broadcastTokenChanged(id);
    }

    /** 额度调整：改 Key 子限额 quotaUsd（USD·null/≤0=不设上限·仅受项目池约束）。复用三级闸门·非独立钱包。 */
    public void adjustQuota(Long id, BigDecimal quotaUsd) {
        NaiToken existing = requireStandalone(id);
        tokenMapper.update(null, new LambdaUpdateWrapper<NaiToken>()
                .eq(NaiToken::getId, id)
                .set(NaiToken::getQuotaUsd, normalizeNullablePositiveUsd(quotaUsd)));
        invalidateQuota(existing);
        broadcastTokenChanged(id);
    }

    /** 查该 Key 所属项目池/工作区实时可用额度 USD（额度抽屉只读展示·真值在 Redis）。 */
    public Map<String, Object> queryBalance(Long id) {
        NaiToken token = requireStandalone(id);
        String projectCode = token.getProjectCode();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectCode", projectCode);
        result.put("keyQuotaUsd", token.getQuotaUsd());
        boolean unlimited = quotaLedger.isUnlimited(projectCode);
        result.put("projectUnlimited", unlimited);
        result.put("projectBalance", unlimited ? null : UsdAmount.fromMicros(quotaLedger.projectBalance(projectCode)));
        if (token.getWorkspaceId() != null) {
            result.put("workspaceId", token.getWorkspaceId());
            result.put("workspaceBalance", UsdAmount.fromMicros(quotaLedger.workspaceBalance(projectCode, token.getWorkspaceId())));
        }
        return result;
    }

    private NaiToken requireStandalone(Long id) {
        if (id == null) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "token id 必填");
        }
        NaiToken existing = tokenMapper.selectById(id);
        if (existing == null) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "Access Key 不存在: " + id);
        }
        if (!Integer.valueOf(1).equals(existing.getStandalone())) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "仅独立 Key 支持此操作");
        }
        return existing;
    }

    private void requireActiveProject(String projectCode) {
        NaiProject project = projectMapper.selectOne(new LambdaQueryWrapper<NaiProject>()
                .eq(NaiProject::getProjectCode, projectCode));
        if (project == null || project.getStatus() == null || project.getStatus() != 1) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "项目不存在或已归档: " + projectCode);
        }
    }

    private void validateWorkspace(String projectCode, Long workspaceId) {
        if (workspaceId == null) {
            return;
        }
        NaiWorkspace workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectCode.equals(workspace.getProjectCode())) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "工作区不存在或不属于项目: " + workspaceId);
        }
    }

    private void invalidateQuota(NaiToken token) {
        if (StringUtils.hasText(token.getProjectCode()) && token.getId() != null) {
            quotaLedger.invalidateToken(token.getProjectCode(), token.getId());
        }
    }

    private void broadcastTokenChanged(Long tokenId) {
        long version = tokenId == null ? 0L : tokenId;
        eventPublisher.publishEvent(ConfigChangedEvent.token(version));
        redisson.getTopic(GatewayConstants.TOKEN_SYNC_TOPIC, StringCodec.INSTANCE)
                .publish(String.valueOf(version));
    }

    private GeneratedKey generateUniqueKey() {
        for (int i = 0; i < MAX_GENERATE_ATTEMPTS; i++) {
            String plain = generatePlainKey();
            String hash = sha256(plain);
            NaiToken exists = tokenMapper.selectOne(new LambdaQueryWrapper<NaiToken>().eq(NaiToken::getKeyHash, hash));
            if (exists == null) {
                return new GeneratedKey(plain, hash);
            }
        }
        throw GatewayException.of(GatewayErrorCode.INTERNAL_ERROR, "生成 Access Key 冲突次数过多");
    }

    private static String generatePlainKey() {
        byte[] buf = new byte[GENERATED_KEY_BYTES];
        RANDOM.nextBytes(buf);
        return "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw GatewayException.of(GatewayErrorCode.INTERNAL_ERROR, "SHA-256 计算失败");
        }
    }

    private static String normalizeName(String value) {
        String name = requireText(value, "name 必填");
        if (name.length() > 100) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "name 不能超过 100 个字符");
        }
        return name;
    }

    private static String normalizeGroup(String value) {
        String group = StringUtils.hasText(value) ? value.trim() : "default";
        if (group.length() > 64) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "groupCode 不能超过 64 个字符");
        }
        return group;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static BigDecimal normalizeNullablePositiveUsd(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            return null;
        }
        return value;
    }

    private static Integer normalizeNonNegative(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "限流值不能为负数");
        }
        return value;
    }

    private static Long normalizeNonNegativeLong(Long value) {
        if (value == null) {
            return 0L;
        }
        if (value < 0) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, "过期时间不能为负数");
        }
        return value;
    }

    private static Integer normalizeStatus(Integer value) {
        return value == null || value != 1 ? 0 : 1;
    }

    private static Integer normalizeBoolFlag(Integer value) {
        return Integer.valueOf(1).equals(value) ? 1 : 0;
    }

    private static String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw GatewayException.of(GatewayErrorCode.BAD_REQUEST, message);
        }
        return value.trim();
    }

    private record GeneratedKey(String plain, String hash) {
    }
}
