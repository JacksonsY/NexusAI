package com.nexusai.router;

import com.nexusai.limit.ConcurrencySlot;
import com.nexusai.persistence.po.NaiChannel;
import com.nexusai.persistence.po.NaiChannelKey;
import com.nexusai.router.channel.ChannelRouter;
import com.nexusai.router.keypool.KeyPoolSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RouteServiceTest {

    private RouteService routeService;
    private NaiChannel channel;

    @BeforeEach
    void setUp() {
        channel = new NaiChannel();
        channel.setId(100L);
        channel.setName("openai");

        NaiChannelKey key = new NaiChannelKey();
        key.setId(200L);
        key.setChannelId(channel.getId());

        ChannelRouter channelRouter = new ChannelRouter(null, null, null) {
            @Override
            public NaiChannel selectChannel(String group, String model, String affinity, Set<Long> excludeChannelIds) {
                return channel;
            }
        };
        KeyPoolSelector keyPoolSelector = new KeyPoolSelector(null, null, null, null, null, null) {
            @Override
            public KeyPoolSelector.Selection select(Long channelId, String affinity, Set<Long> excludeKeyIds) {
                return new KeyPoolSelector.Selection(key, "");
            }
        };
        routeService = new RouteService(channelRouter, keyPoolSelector, new ConcurrencySlot(null));
    }

    @Test
    void routesWithMappedUpstreamModelName() {
        channel.setModelMapping("{\"gpt-4o\":\"gpt-4o-2024-08-06\"}");

        RouteResult result = routeService.route("default", "gpt-4o", "affinity", Set.of(), Set.of());

        assertThat(result.targetModel()).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    void fallsBackToInboundModelWhenMappingIsInvalid() {
        channel.setModelMapping("{bad-json");

        RouteResult result = routeService.route("default", "gpt-4o", "affinity", Set.of(), Set.of());

        assertThat(result.targetModel()).isEqualTo("gpt-4o");
    }

    @Test
    void routesWithUpdatedMappingWhenRawJsonChanges() {
        channel.setModelMapping("{\"gpt-4o\":\"old-upstream\"}");
        assertThat(routeService.route("default", "gpt-4o", "affinity", Set.of(), Set.of()).targetModel())
                .isEqualTo("old-upstream");

        channel.setModelMapping("{\"gpt-4o\":\"new-upstream\"}");

        assertThat(routeService.route("default", "gpt-4o", "affinity", Set.of(), Set.of()).targetModel())
                .isEqualTo("new-upstream");
    }
}
