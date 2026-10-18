package uk.m4xy.fckt.ocaml

private val KEYWORDS = setOf(
    "and", "as", "assert", "asr", "begin", "class", "constraint", "do", "done", "downto", "else", "end",
    "exception", "external", "false", "for", "fun", "function", "functor", "if", "in", "include", "inherit",
    "initializer", "land", "lazy", "let", "lor", "lsl", "lsr", "lxor", "match", "method", "mod", "module",
    "mutable", "new", "nonrec", "object", "of", "open", "or", "private", "rec", "sig", "struct", "then", "to",
    "true", "try", "type", "val", "virtual", "when", "while", "with",
)

/** `orderId` → `order_id`, `UserId` → `user_id`, `HTTPCode` → `http_code`; OCaml keywords get a `_` suffix. */
fun snake(name: String): String {
    val out = StringBuilder()
    for ((i, c) in name.withIndex()) {
        if (c.isUpperCase() && i > 0) {
            val prev = name[i - 1]
            val nextLower = name.getOrNull(i + 1)?.isLowerCase() == true
            if (prev.isLowerCase() || prev.isDigit() || (prev.isUpperCase() && nextLower)) out.append('_')
        }
        out.append(c.lowercaseChar())
    }
    val s = out.toString()
    return if (s in KEYWORDS) "${s}_" else s
}

/** A type parameter `T` as an OCaml type variable `'t`. */
fun typeVar(name: String) = "'" + name.lowercase()

/** An OCaml string literal. */
fun quote(text: String): String {
    val out = StringBuilder("\"")
    for (c in text) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\t' -> out.append("\\t")
            '\r' -> out.append("\\r")
            else -> if (c.code < 0x20) out.append("\\%03d".format(c.code)) else out.append(c)
        }
    }
    return out.append('"').toString()
}
