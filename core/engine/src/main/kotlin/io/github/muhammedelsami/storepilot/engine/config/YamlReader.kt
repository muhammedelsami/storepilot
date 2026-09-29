package io.github.muhammedelsami.storepilot.engine.config

import io.github.muhammedelsami.storepilot.api.Problem
import io.github.muhammedelsami.storepilot.api.ValidationException
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Compose
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.nodes.MappingNode
import org.snakeyaml.engine.v2.nodes.Node
import org.snakeyaml.engine.v2.nodes.ScalarNode
import org.snakeyaml.engine.v2.nodes.SequenceNode
import org.snakeyaml.engine.v2.nodes.Tag

/**
 * Reads YAML nodes and collects problems with their line and column. Scalars are read as written,
 * so `0555` stays `0555` and does not become a number.
 */
internal open class YamlReader(protected val source: String) {
    val problems = mutableListOf<Problem>()

    class Entry(val key: String, val keyNode: Node, val value: Node)

    /** The entries of a mapping node, or null (with a problem) when [node] is not a mapping. */
    fun entries(node: Node, what: String): List<Entry>? {
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

    fun scalar(node: Node, key: String): String? {
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

    fun <T> convert(node: Node, key: String, parse: (String) -> T): T? {
        val value = scalar(node, key) ?: return null
        return try {
            parse(value)
        } catch (e: IllegalArgumentException) {
            error(node, "Invalid '$key': ${e.message}")
            null
        }
    }

    fun boolean(node: Node, key: String): Boolean? =
        convert(node, key) { it.toBooleanStrictOrNull() ?: throw IllegalArgumentException("expected true or false, got '$it'") }

    /** A list of plain values, each with its node for messages. */
    fun scalarList(node: Node, key: String): List<Pair<String, Node>>? {
        if (node !is SequenceNode) {
            error(node, "'$key' must be a list.")
            return null
        }
        return node.value.mapNotNull { item -> scalar(item, key)?.let { it to item } }
    }

    fun error(node: Node, message: String) {
        val location = node.startMark.map { "$source:${it.line + 1}:${it.column + 1}" }.orElse(source)
        problems += Problem.error(message, location)
    }

    companion object {
        /** The root node, or null for an empty document. */
        fun compose(text: String, source: String): Node? =
            try {
                Compose(LoadSettings.builder().setLabel(source).build()).composeString(text).orElse(null)
            } catch (e: YamlEngineException) {
                throw ValidationException(Problem.error("Not valid YAML: ${e.message}", source))
            }
    }
}
