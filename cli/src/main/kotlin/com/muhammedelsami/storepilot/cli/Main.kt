package com.muhammedelsami.storepilot.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.singleOrNull() == "--version") {
        println("storepilot ${version()}")
        return
    }
    System.err.println("Usage: storepilot --version")
    System.err.println("No commands are implemented yet.")
    exitProcess(1)
}

private fun version(): String =
    object {}.javaClass.`package`?.implementationVersion ?: "unknown"
