package io.github.muhammedelsami.storepilot.cli

import com.github.ajalt.clikt.core.main

fun main(args: Array<String>) = storePilotCli(CliEnvironment.system()).main(args)
