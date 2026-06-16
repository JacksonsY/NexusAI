package com.nexusai.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @AfterEach
    void resetTrustedProxies() {
        ClientIp.setTrustedProxies(List.of());
    }

    @Test
    void ignoresForwardedHeaderWhenTrustedProxyIsUnset() {
        ClientIp.setTrustedProxies(List.of());

        String ip = ClientIp.resolve("10.0.0.8", "198.51.100.7");

        assertThat(ip).isEqualTo("10.0.0.8");
    }

    @Test
    void ignoresForwardedHeaderWhenPeerIsNotTrusted() {
        ClientIp.setTrustedProxies(List.of("10.0.0.0/24"));

        String ip = ClientIp.resolve("172.16.0.5", "198.51.100.7");

        assertThat(ip).isEqualTo("172.16.0.5");
    }

    @Test
    void returnsRightMostUntrustedHopFromTrustedProxyChain() {
        ClientIp.setTrustedProxies(List.of("10.0.0.0/24", "192.0.2.0/24"));

        String ip = ClientIp.resolve("10.0.0.8", "198.51.100.7, 192.0.2.10");

        assertThat(ip).isEqualTo("198.51.100.7");
    }

    @Test
    void skipsInvalidForwardedHeaderHops() {
        ClientIp.setTrustedProxies(List.of("10.0.0.0/24"));

        String ip = ClientIp.resolve("10.0.0.8", "unknown, , 198.51.100.7, 10.0.0.9");

        assertThat(ip).isEqualTo("198.51.100.7");
    }

    @Test
    void fallsBackToPeerWhenAllForwardedHopsAreTrustedOrInvalid() {
        ClientIp.setTrustedProxies(List.of("10.0.0.0/24"));

        String ip = ClientIp.resolve("10.0.0.8", "unknown, 10.0.0.9");

        assertThat(ip).isEqualTo("10.0.0.8");
    }

    @Test
    void supportsExactIpv6TrustedProxy() {
        ClientIp.setTrustedProxies(List.of("2001:db8::2"));

        String ip = ClientIp.resolve("2001:db8::2", "2001:db8::1");

        assertThat(ip).isEqualTo("2001:db8::1");
    }

    @Test
    void trimsTrustedProxyEntriesConfiguredDirectly() {
        ClientIp.setTrustedProxies(List.of(" 10.0.0.0/24 ", "", "   "));

        String ip = ClientIp.resolve("10.0.0.8", "198.51.100.7, 10.0.0.9");

        assertThat(ip).isEqualTo("198.51.100.7");
    }

    @Test
    void skipsMalformedIpv4AndIpv6ForwardedHops() {
        ClientIp.setTrustedProxies(List.of("10.0.0.0/24"));

        String ip = ClientIp.resolve("10.0.0.8", "999.1.1.1, 2001:db8:::1, 198.51.100.7, 10.0.0.9");

        assertThat(ip).isEqualTo("198.51.100.7");
    }

    @Test
    void supportsCompressedIpv6ClientHop() {
        ClientIp.setTrustedProxies(List.of("2001:db8::2"));

        String ip = ClientIp.resolve("2001:db8::2", "2001:db8:abcd::1, 2001:db8::2");

        assertThat(ip).isEqualTo("2001:db8:abcd::1");
    }
}
