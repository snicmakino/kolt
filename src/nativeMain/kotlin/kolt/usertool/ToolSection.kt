package kolt.usertool

import kolt.resolve.Coordinate

/**
 * Validated `[tools.<alias>]` entry, surfaced via `KoltConfig.tools`.
 *
 * `coords` is the parsed Maven coordinate; `classifier` holds the optional fourth segment of
 * `group:artifact:version[:classifier]`. Construction is restricted to the parse pipeline in
 * `ToolSectionParse`, so any `ToolEntry` reaching downstream code is already validated.
 */
data class ToolEntry(val coords: Coordinate, val classifier: String?)
