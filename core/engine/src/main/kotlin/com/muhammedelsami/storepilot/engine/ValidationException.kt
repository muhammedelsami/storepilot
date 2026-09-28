package com.muhammedelsami.storepilot.engine

import com.muhammedelsami.storepilot.api.Problem
import com.muhammedelsami.storepilot.api.StorePilotException

/**
 * Invalid config or input, found before or instead of a store change. Entry points map it to exit
 * code 1. [problems] can also hold warnings; the message lists only the errors.
 */
class ValidationException(val problems: List<Problem>) :
    StorePilotException(problems.filter { it.isError }.joinToString("\n")) {
    constructor(problem: Problem) : this(listOf(problem))
}
