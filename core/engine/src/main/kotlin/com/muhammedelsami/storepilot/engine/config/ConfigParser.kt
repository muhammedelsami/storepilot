package com.muhammedelsami.storepilot.engine.config

import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.ReleaseStatus
import com.muhammedelsami.storepilot.api.Rollout
import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.engine.ValidationException
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.Tag
import java.nio.file.Path
import kotlin.io.path.readText

/** Reads `storepilot.yml` (docs/design.md §6). All problems in a file are reported together. */
object ConfigParser {
    const val FILE_NAME = "storepilot.yml"
    const val VERSION = "1"

    fun load(path: Path): StorePilotConfig = parse(path.readText(), path.toString())

    /** [source] names the file in problem messages. */
    fun parse(text: String, source: String = FILE_NAME): StorePilotConfig {
        val root = try {
            Compose(LoadSettings.builder().setLabel(source).build()).composeString(text).orElse(null)
        } catch (e: YamlEngineException) {
            throw ValidationException(Problem.error("Not valid YAML: ${e.message}", source))
        }
        val reader = NodeReader(source)
        val config = reader.readConfig(root)
        if (reader.problems.isNotEmpty()) throw ValidationException(reader.problems)
        return config
    }
}

private class NodeReader(private val source: String) {
    val problems = mutableListOf<Problem>()

    fun readConfig(root: Node?): StorePilotConfig {
        if (root == null) {
            problems += Problem.error("The file is empty. It needs at least 'version: ${ConfigParser.VERSION}'.", source)
            return StorePilotConfig()
        }
        var metadataDir: String? = null
        var track: Track? = null
        var rollout: Rollout? = null
        var onUnsupported: OnUnsupported? = null
        var stores: Map<StoreId, StoreConfig> = emptyMap()
        var hasVersion = false
        for (entry in entries(root, "The file") ?: return StorePilotConfig()) {
            val value = entry.value
            when (entry.key) {
                "version" -> {
                    hasVersion = true
                    val version = scalar(value, "version")
                    if (version != null && version != ConfigParser.VERSION) {
                        error(value, "Unsupported version '$version'. This StorePilot reads version ${ConfigParser.VERSION}.")
                    }
                }
                "metadataDir" -> metadataDir = scalar(value, entry.key)
                "track" -> track = convert(value, entry.key, ::Track)
                "rollout" -> rollout = convert(value, entry.key, ::parseRollout)
                "onUnsupported" -> onUnsupported = convert(value, entry.key) { parseConfigEnum<OnUnsupported>(it) }
                "stores" -> stores = readStores(value)
                else -> error(entry.keyNode, "Unknown key '${entry.key}'.")
            }
        }
        if (!hasVersion) error(root, "Missing 'version: ${ConfigParser.VERSION}'.")
        return StorePilotConfig(metadataDir, track, rollout, onUnsupported, stores)
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

    private class Entry(val key: String, val keyNode: Node, val value: Node)

    /** The entries of a mapping node, or null (with a problem) when [node] is not a mapping. */
    private fun entries(node: Node, what: String): List<Entry>? {
        if (node !is MappingNode) {
            error(node, "$what must be a mapping of keys to values.")
            return null
        }
        val seen = mutableSetOf<String>()
        return node.value.mapNotNull { tuple ->
            val keyNode = tuple.keyNode
            val key = (keyNode as? ScalarNode)?.value
            when {
                key == null -> {
                    error(keyNode, "Keys must be plain text.")
                    null
                }
                !seen.add(key) -> {
                    error(keyNode, "Duplicate key '$key'.")
                    null
                }
                else -> Entry(key, keyNode, tuple.valueNode)
            }
        }
    }

    private fun scalar(node: Node, key: String): String? {
        if (node !is ScalarNode) {
            error(node, "'$key' must be a single value.")
            return null
        }
        if (node.tag == Tag.NULL) {
            error(node, "'$key' has no value.")
            return null
        }
        return node.value
    }

    private fun <T> convert(node: Node, key: String, parse: (String) -> T): T? {
        val value = scalar(node, key) ?: return null
        return try {
            parse(value)
        } catch (e: IllegalArgumentException) {
            error(node, "Invalid '$key': ${e.message}")
            null
        }
    }

    private fun error(node: Node, message: String) {
        val location = node.startMark.map { "$source:${it.line + 1}:${it.column + 1}" }.orElse(source)
        problems += Problem.error(message, location)
    }
}
