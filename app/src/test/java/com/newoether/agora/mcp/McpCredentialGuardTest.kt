package com.newoether.agora.mcp

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP cleartext-credential guard is intentionally a no-op: MCP servers are user-supplied
 * endpoints, so a self-hosted or public http:// endpoint must remain reachable even when the
 * user configured custom header credentials. This is deliberately narrower than the provider
 * guard in [com.newoether.agora.api.HttpClient.guardCleartextCredentials], which still refuses
 * to send credentials over cleartext to non-local provider hosts.
 */
class McpCredentialGuardTest {

    @Test
    fun publicHttpEndpointWithCredentials_isNotBlocked() {
        // A throw here would fail the test: the guard must permit cleartext to public http.
        guardMcpCredentials(
            endpoint = "http://example.com:8080/mcp",
            headers = mapOf("Authorization" to "Bearer secret-token"),
        )
        assertTrue(true)
    }

    @Test
    fun publicHttpEndpointWithoutHeaders_isNotBlocked() {
        guardMcpCredentials(
            endpoint = "http://api.example.com/mcp",
            headers = emptyMap(),
        )
        assertTrue(true)
    }

    @Test
    fun localHttpEndpointWithCredentials_isNotBlocked() {
        guardMcpCredentials(
            endpoint = "http://192.168.1.10:9000/mcp",
            headers = mapOf("x-api-key" to "local-key"),
        )
        assertTrue(true)
    }

    @Test
    fun httpsEndpoint_isNotBlocked() {
        guardMcpCredentials(
            endpoint = "https://secure.example.com/mcp",
            headers = mapOf("Authorization" to "Bearer secret"),
        )
        assertTrue(true)
    }
}
