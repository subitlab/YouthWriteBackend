@file:Suppress("PackageDirectoryMismatch")

package subit.plugin.autoHead

import io.ktor.server.application.*
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.*

fun Application.installAutoHead() = install(AutoHeadResponse)