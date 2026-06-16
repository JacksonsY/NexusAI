package com.nexusai.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IpMatcherTest {

    @Test
    void matchesIpv4CidrWithoutRegexSplitting() {
        assertThat(IpMatcher.inWhitelist("10.0.0.8", List.of("10.0.0.0/24"))).isTrue();
        assertThat(IpMatcher.inWhitelist("10.0.1.8", List.of("10.0.0.0/24"))).isFalse();
    }

    @Test
    void rejectsMalformedIpv4Literals() {
        assertThat(IpMatcher.isIpLiteral("999.0.0.1")).isFalse();
        assertThat(IpMatcher.isIpLiteral("10..0.1")).isFalse();
        assertThat(IpMatcher.isIpLiteral("10.0.0.1.1")).isFalse();
        assertThat(IpMatcher.isIpLiteral("10.0.0")).isFalse();
    }

    @Test
    void validatesIpv6LiteralsWithoutNameResolution() {
        assertThat(IpMatcher.isIpLiteral("2001:db8::1")).isTrue();
        assertThat(IpMatcher.isIpLiteral("::ffff:192.0.2.1")).isTrue();
        assertThat(IpMatcher.isIpLiteral("2001:db8:::1")).isFalse();
        assertThat(IpMatcher.isIpLiteral("2001:db8::1%lo0")).isFalse();
    }

    @Test
    void validatesWhitelistEntriesStrictly() {
        assertThat(IpMatcher.isValidEntry("192.0.2.0/24")).isTrue();
        assertThat(IpMatcher.isValidEntry("2001:db8::1")).isTrue();
        assertThat(IpMatcher.isValidEntry("2001:db8::1/64")).isFalse();
        assertThat(IpMatcher.isValidEntry("bad:entry")).isFalse();
    }
}
