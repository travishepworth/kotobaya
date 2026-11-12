package kotobaya.core

import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

/** Trivial exported function; proves web/ can import from `@kotobaya/core`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
public fun coreVersion(): String = "kotobaya-core-0"
