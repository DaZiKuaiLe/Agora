package com.newoether.agora.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperOptionsPersistenceContractTest {
    @Test
    fun developerOptionsKeyRemainsStable() {
        assertEquals("developer_options_enabled", DEVELOPER_OPTIONS_ENABLED.name)
    }
}
