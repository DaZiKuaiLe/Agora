package com.newoether.agora.tool

import java.util.UUID

data class AsyncCommandId(
    val site: Site,
    val deviceId: String?,
    val runId: String,
) {
    enum class Site { LOCAL, SSH }

    fun encode(): String = when (site) {
        Site.LOCAL -> "local:$runId"
        Site.SSH -> "ssh:${deviceId.orEmpty()}:$runId"
    }

    companion object {
        fun local(runId: String = UUID.randomUUID().toString()): AsyncCommandId =
            AsyncCommandId(Site.LOCAL, null, runId)

        fun ssh(deviceId: String, runId: String = UUID.randomUUID().toString()): AsyncCommandId =
            AsyncCommandId(Site.SSH, deviceId, runId)

        fun parse(raw: String): AsyncCommandId? {
            val value = raw.trim()
            if (value.startsWith("local:")) {
                val runId = value.removePrefix("local:")
                if (runId.isBlank()) return null
                return AsyncCommandId(Site.LOCAL, null, runId)
            }
            if (value.startsWith("ssh:")) {
                val rest = value.removePrefix("ssh:")
                val cut = rest.lastIndexOf(':')
                if (cut <= 0 || cut == rest.lastIndex) return null
                val deviceId = rest.substring(0, cut)
                val runId = rest.substring(cut + 1)
                if (deviceId.isBlank() || runId.isBlank()) return null
                return AsyncCommandId(Site.SSH, deviceId, runId)
            }
            return null
        }
    }
}
