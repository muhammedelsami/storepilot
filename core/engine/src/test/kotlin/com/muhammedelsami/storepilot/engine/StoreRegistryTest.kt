package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.ValidationException
import com.muhammedelsami.storepilot.api.fake.FakeStoreProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StoreRegistryTest {
    @Test
    fun `discovers adapters with ServiceLoader`() {
        val registry = StoreRegistry.discover()

        assertIs<FakeStoreProvider>(registry[StoreId("fake")])
    }

    @Test
    fun `unknown store lists the available ones`() {
        val registry = StoreRegistry(listOf(FakeStoreProvider(), FakeStoreProvider(id = StoreId("another"))))

        val e = assertFailsWith<ValidationException> { registry[StoreId("google-play")] }
        assertEquals("No adapter for store 'google-play'. Available: another, fake", e.problems.single().message)
    }

    @Test
    fun `two adapters for one store are rejected`() {
        assertFailsWith<IllegalArgumentException> { StoreRegistry(listOf(FakeStoreProvider(), FakeStoreProvider())) }
    }
}
