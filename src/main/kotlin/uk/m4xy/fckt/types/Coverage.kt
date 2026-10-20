package uk.m4xy.fckt.types

/**
 * What a pattern matches, for checking that a `when` covers every value: a constructor applied
 * to patterns for its fields, or, with no [ctor], anything.
 */
class Pat(val ctor: Ctor?, val args: List<Pat> = emptyList()) {
    companion object {
        val ANY = Pat(null)
    }
}

/** One of the shapes a value of a type can have. */
sealed interface Ctor {
    /** A case of a type with cases, by the type that declares it, so a shared case is one case. */
    data class Case(val origin: String, val name: String) : Ctor
    data class Bool(val value: Boolean) : Ctor
    data object Nil : Ctor
    data object Cons : Ctor
    data class Tuple(val size: Int) : Ctor
    data class Record(val name: String) : Ctor

    /** A number, string or character: there are always more than any `when` lists. */
    data class Literal(val text: String) : Ctor
}

/**
 * Whether a `when`'s patterns cover every value, by Maranget's usefulness check ("Warnings for
 * pattern matching", 2007), as OCaml's compiler does: patterns cover everything when a pattern
 * that matches anything would match nothing they don't. [signature] says what shapes a type's
 * values can have, with the types of their fields, or null when there are too many to list.
 */
class Coverage(private val signature: (Type) -> List<Pair<Ctor, List<Type>>>?) {
    /** Examples of values of [type] that none of [patterns] match, one for each shape that has some. */
    fun missing(patterns: List<Pat>, type: Type): List<Pat> {
        val rows = patterns.map { listOf(it) }
        val shapes = signature(type) ?: return listOfNotNull(useful(rows, listOf(type))?.first())
        return shapes.mapNotNull { (ctor, fields) ->
            useful(specialize(rows, ctor, fields.size), fields)?.let { Pat(ctor, it) }
        }
    }

    /**
     * A row of values, of [types], that no row of [rows] matches; or null if every row of values
     * is matched by one of them. Values in the result that any value would do for are [Pat.ANY].
     */
    private fun useful(rows: List<List<Pat>>, types: List<Type>): List<Pat>? {
        if (types.isEmpty()) return if (rows.isEmpty()) emptyList() else null
        val type = types[0]
        val rest = types.drop(1)
        val heads = rows.mapNotNull { it[0].ctor }.toSet()
        val shapes = signature(type)
        if (shapes != null && heads.isNotEmpty() && shapes.all { it.first in heads }) {
            // Every shape is matched by some row: what's missing, if anything, is inside one.
            for ((ctor, fields) in shapes) {
                val found = useful(specialize(rows, ctor, fields.size), fields + rest) ?: continue
                return listOf(Pat(ctor, found.take(fields.size))) + found.drop(fields.size)
            }
            return null
        }
        // Some shape isn't matched by any row's first pattern: those that match anything decide.
        val found = useful(rows.filter { it[0].ctor == null }.map { it.drop(1) }, rest) ?: return null
        // No row looks at this value at all: any value of it will do.
        val unmatched = if (heads.isEmpty()) null else shapes?.firstOrNull { it.first !in heads }?.let { (ctor, fields) -> Pat(ctor, fields.map { Pat.ANY }) }
        return listOf(unmatched ?: Pat.ANY) + found
    }

    /** The rows that can match [ctor], with its fields' patterns in place of their first. */
    private fun specialize(rows: List<List<Pat>>, ctor: Ctor, arity: Int): List<List<Pat>> = rows.mapNotNull { row ->
        val head = row[0]
        when (head.ctor) {
            null -> List(arity) { Pat.ANY } + row.drop(1)
            ctor -> head.args + row.drop(1)
            else -> null
        }
    }

    companion object {
        /** [p] as fckt writes it: `B`, `Some(Error(_))`, `[_, *_]`, `(true, _)`. */
        fun show(p: Pat): String = when (val c = p.ctor) {
            null -> "_"
            is Ctor.Case -> if (p.args.isEmpty()) c.name else "${c.name}(${p.args.joinToString(", ") { show(it) }})"
            is Ctor.Bool -> c.value.toString()
            is Ctor.Tuple -> "(${p.args.joinToString(", ") { show(it) }})"
            is Ctor.Record -> "${c.name.substringAfterLast('.')}(${p.args.joinToString(", ") { show(it) }})"
            is Ctor.Literal -> c.text
            Ctor.Nil -> "[]"
            Ctor.Cons -> {
                // A list's elements up to its end, or to the rest of it: [a, b] or [a, *_].
                val items = mutableListOf<String>()
                var at = p
                while (at.ctor == Ctor.Cons) {
                    items += show(at.args[0])
                    at = at.args[1]
                }
                if (at.ctor == Ctor.Nil) "[${items.joinToString(", ")}]" else "[${(items + "*${show(at)}").joinToString(", ")}]"
            }
        }
    }
}
