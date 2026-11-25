package com.newoether.agora.data

/**
 * Single-summary compact (retain window + one digest). Archive-band flag still gates
 * whether compact runs; integration band stays off.
 */
object TimeBandPolicy {
    const val ARCHIVE_BAND_ENABLED = true
    const val INTEGRATION_BAND_ENABLED = false
    const val TURN_INTEGRATIONS_TYPE = "turn_integrations"
    const val TURN_INTEGRATION_TYPE = "turn_integration"
    const val TURN_INTEGRATION_ID_PREFIX = "context_integrate_"
    const val TURN_INTEGRATION_TOOL = "turn_integration"
}
