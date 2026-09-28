package com.muhammedelsami.storepilot.api

/** A validation finding. [source] is the file, config key, or other place it refers to. */
data class Problem(
    val severity: Severity,
    val message: String,
    val source: String? = null,
) {
    enum class Severity { ERROR, WARNING }

    val isError: Boolean
        get() = severity == Severity.ERROR

    override fun toString(): String =
        (if (source != null) "$source: " else "") + severity.name.lowercase() + ": " + message

    companion object {
        fun error(message: String, source: String? = null) = Problem(Severity.ERROR, message, source)

        fun warning(message: String, source: String? = null) = Problem(Severity.WARNING, message, source)
    }
}

/** A credential value. [toString] never shows it. */
class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "****"
}

/**
 * Credentials for one store, by field name in kebab case, for example `service-account-json`.
 * The CLI reads them from `STOREPILOT_<STORE_ID>_<FIELD>` environment variables; the Gradle plugin
 * from the DSL.
 */
fun interface Secrets {
    operator fun get(field: String): Secret?
}

interface Logger {
    fun info(message: String)

    fun warn(message: String)

    companion object {
        val NONE: Logger = object : Logger {
            override fun info(message: String) = Unit

            override fun warn(message: String) = Unit
        }
    }
}
