package utils

fun csvValue(value: String): String {
    val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } || value == "\\N"
    if (!needsQuoting) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}
