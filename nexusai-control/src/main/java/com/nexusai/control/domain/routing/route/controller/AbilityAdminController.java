package com.nexusai.control.domain.routing.route.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexusai.common.model.R;
import com.nexusai.control.api.contract.ConfigChangePublisher;
import com.nexusai.persistence.mapper.NaiAbilityMapper;
import com.nexusai.persistence.po.NaiAbility;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 路由索引(nai_ability)运营 CRUD —— 两级调度第一级"选渠道"：(group,model) → 哪些渠道 + priority/weight/enabled
 * （ChannelRouter 第一级数据源）。
 * <p>nai_ability 为复合主键(group_code,model,channel_id) 且 {@link NaiAbility} 无 id，
 * 故用复合键 upsert/delete（不能 updateById/deleteById）。
 * <p>save/delete 后广播配置变更：{@code RouteCache} 第一级 ability 缓存即时失效（本节点 + 跨实例），
 * 不广播则新增/删除的渠道选择映射要等最长一个 TTL 周期才生效。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/abilities")
@RequiredArgsConstructor
public class AbilityAdminController {

    private final NaiAbilityMapper abilityMapper;
    private final ConfigChangePublisher configChangePublisher;

    @GetMapping
    public Mono<R<List<NaiAbility>>> list(@RequestParam(required = false) String model,
                                          @RequestParam(required = false) String groupCode,
                                          @RequestParam(required = false) Long channelId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<NaiAbility> qw = Wrappers.lambdaQuery(NaiAbility.class)
                    .eq(StringUtils.hasText(model), NaiAbility::getModel, model)
                    .eq(StringUtils.hasText(groupCode), NaiAbility::getGroupCode, groupCode)
                    .eq(channelId != null, NaiAbility::getChannelId, channelId)
                    .orderByDesc(NaiAbility::getPriority);
            return R.ok(abilityMapper.selectList(qw));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** upsert：按复合键命中则更新 priority/weight/enabled/modality，否则插入新行。 */
    @PostMapping
    public Mono<R<Void>> save(@RequestBody NaiAbility a) {
        return Mono.fromCallable(() -> {
            LambdaUpdateWrapper<NaiAbility> uw = Wrappers.lambdaUpdate(NaiAbility.class)
                    .eq(NaiAbility::getGroupCode, a.getGroupCode())
                    .eq(NaiAbility::getModel, a.getModel())
                    .eq(NaiAbility::getChannelId, a.getChannelId())
                    .set(NaiAbility::getPriority, a.getPriority())
                    .set(NaiAbility::getWeight, a.getWeight())
                    .set(NaiAbility::getEnabled, a.getEnabled())
                    .set(NaiAbility::getModality, a.getModality());
            int n = abilityMapper.update(null, uw);
            if (n == 0) {
                abilityMapper.insert(a);
            }
            configChangePublisher.publish();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping
    public Mono<R<Void>> delete(@RequestParam String groupCode,
                                @RequestParam String model,
                                @RequestParam Long channelId) {
        return Mono.fromCallable(() -> {
            abilityMapper.delete(Wrappers.lambdaQuery(NaiAbility.class)
                    .eq(NaiAbility::getGroupCode, groupCode)
                    .eq(NaiAbility::getModel, model)
                    .eq(NaiAbility::getChannelId, channelId));
            configChangePublisher.publish();
            return R.<Void>ok();
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
