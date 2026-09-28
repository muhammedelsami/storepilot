package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.StoreProvider
import com.muhammedelsami.storepilot.api.ValidationException
import java.util.ServiceLoader

/** The store adapters that are available. */
class StoreRegistry(providers: Iterable<StoreProvider>) {
    private val providers: Map<StoreId, StoreProvider>

    init {
        val byId = providers.groupBy { it.id }
        val duplicates = byId.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
            "More than one adapter for store " + duplicates.entries.joinToString { (id, list) ->
                "'$id' (${list.joinToString { it.javaClass.name }})"
            }
        }
        this.providers = byId.mapValues { it.value.single() }
    }

    val ids: Set<StoreId>
        get() = providers.keys

    operator fun get(id: StoreId): StoreProvider =
        providers[id] ?: throw ValidationException(
            Problem.error("No adapter for store '$id'. Available: " + ids.sortedBy { it.value }.joinToString().ifEmpty { "none" }),
        )

    companion object {
        /** Finds adapters with [ServiceLoader] in [classLoader]. */
        fun discover(classLoader: ClassLoader = StoreRegistry::class.java.classLoader): StoreRegistry =
            StoreRegistry(ServiceLoader.load(StoreProvider::class.java, classLoader))
    }
}
