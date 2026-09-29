package io.github.muhammedelsami.storepilot.api

/**
 * Invalid config, credentials, or input, found before or instead of a store change. Entry points
 * map it to exit code 1. [problems] can also hold warnings; the message lists only the errors.
 */
class ValidationException(val problems: List<Problem>) :
    StorePilotException(problems.filter { it.isError }.joinToString("\n")) {
    constructor(problem: Problem) : this(listOf(problem))
}
