package io.github.muhammedelsami.storepilot.engine.config

import io.github.muhammedelsami.storepilot.api.Problem
import io.github.muhammedelsami.storepilot.api.ReleaseStatus
import io.github.muhammedelsami.storepilot.api.Rollout
import io.github.muhammedelsami.storepilot.api.StoreId
import io.github.muhammedelsami.storepilot.api.Track
import io.github.muhammedelsami.storepilot.api.ValidationException
import io.github.muhammedelsami.storepilot.engine.listing.LintRule
import org.snakeyaml.engine.v2.nodes.Node
import java.nio.file.Path
import kotlin.io.path.readText

/** Reads `storepilot.yml` (docs/design.md §6). All problems in a file are reported together. */
object ConfigParser {
    const val FILE_NAME = "storepilot.yml"
    const val VERSION = "1"

    fun load(path: Path): StorePilotConfig = parse(path.readText(), path.toString())

    /** [source] names the file in problem messages. */
    fun parse(text: String, source: String = FILE_NAME): StorePilotConfig {
        val reader = ConfigReader(source)
        val config = reader.readConfig(YamlReader.compose(text, source))
        if (reader.problems.isNotEmpty()) throw ValidationException(reader.problems)
        return config
    }
}

private class ConfigReader(source: String) : YamlReader(source) {
    fun readConfig(root: Node?): StorePilotConfig {
        if (root == null) {
            problems += Problem.error(
                "The file is empty. It needs at least 'version: ${ConfigParser.VERSION}'.",
                source,
            )
            return StorePilotConfig()
        }
        var config = StorePilotConfig()
        var hasVersion = false
        for (entry in entries(root, "The file") ?: return config) {
            val value = entry.value
            val key = entry.key
            when (key) {
                "version" -> {
                    hasVersion = true
                    val version = scalar(value, key)
                    if (version != null && version != ConfigParser.VERSION) {
                        error(value, "Unsupported version '$version'. This StorePilot reads version ${ConfigParser.VERSION}.")
                    }
                }
                "metadataDir" -> config = config.copy(metadataDir = scalar(value, key))
                "track" -> config = config.copy(track = convert(value, key, ::Track))
                "rollout" -> config = config.copy(rollout = convert(value, key, ::parseRollout))
                "onUnsupported" -> config = config.copy(onUnsupported = convert(value, key) { parseConfigEnum<OnUnsupported>(it) })
                "fallbackToDefaultLanguage" -> config = config.copy(fallbackToDefaultLanguage = boolean(value, key))
                "listing" -> config = config.copy(listing = readListing(value))
                "aso" -> config = config.copy(aso = readAso(value))
                "stores" -> config = config.copy(stores = readStores(value))
                else -> error(entry.keyNode, "Unknown key '$key'.")
            }
        }
        if (!hasVersion) error(root, "Missing 'version: ${ConfigParser.VERSION}'.")
        return config
    }

    private fun readListing(node: Node): ListingConfig {
        var listing = ListingConfig()
        for (entry in entries(node, "'listing'") ?: return listing) {
            val key = "listing.${entry.key}"
            when (entry.key) {
                "graphics" -> listing = listing.copy(graphics = boolean(entry.value, key))
                "replaceScreenshots" -> listing = listing.copy(replaceScreenshots = boolean(entry.value, key))
                else -> error(entry.keyNode, "Unknown key '$key'.")
            }
        }
        return listing
    }

    private fun readAso(node: Node): AsoConfig {
        var aso = AsoConfig()
        for (entry in entries(node, "'aso'") ?: return aso) {
            val key = "aso.${entry.key}"
            when (entry.key) {
                "disable" -> {
                    val rules = scalarList(entry.value, key).orEmpty()
                    for ((id, itemNode) in rules) {
                        if (LintRule.entries.none { it.id == id }) {
                            error(itemNode, "Unknown lint rule '$id'. Rules: ${LintRule.entries.joinToString { it.id }}.")
                        }
                    }
                    aso = aso.copy(disable = rules.map { it.first })
                }
                "warningsAsErrors" -> aso = aso.copy(warningsAsErrors = boolean(entry.value, key))
                else -> error(entry.keyNode, "Unknown key '$key'.")
            }
        }
        return aso
    }

    private fun readStores(node: Node): Map<StoreId, StoreConfig> {
        val stores = mutableMapOf<StoreId, StoreConfig>()
        for (entry in entries(node, "'stores'") ?: return stores) {
            val id = convert(entry.keyNode, "stores", ::StoreId) ?: continue
            stores[id] = readStore(entry.value, "stores.$id")
        }
        return stores
    }

    private fun readStore(node: Node, path: String): StoreConfig {
        var packageName: String? = null
        var track: Track? = null
        var rollout: Rollout? = null
        var releaseStatus: ReleaseStatus? = null
        val options = mutableMapOf<String, String>()
        for (entry in entries(node, "'$path'") ?: return StoreConfig()) {
            val key = "$path.${entry.key}"
            val value = entry.value
            when (entry.key) {
                "packageName" -> packageName = scalar(value, key)
                "track" -> track = convert(value, key, ::Track)
                "rollout" -> rollout = convert(value, key, ::parseRollout)
                "releaseStatus" -> releaseStatus = convert(value, key) { parseConfigEnum<ReleaseStatus>(it) }
                else -> scalar(value, key)?.let { options[entry.key] = it }
            }
        }
        return StoreConfig(packageName, track, rollout, releaseStatus, options)
    }
}
