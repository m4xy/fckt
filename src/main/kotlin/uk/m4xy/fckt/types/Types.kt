package uk.m4xy.fckt.types

/** A type during and after inference. [Var]s are unification variables. */
sealed class Type {
    class Var(val id: Int) : Type() {
        var bound: Type? = null
    }

    data class Con(val name: String, val args: List<Type> = emptyList()) : Type() {
        override fun toString() = render(this, mutableMapOf())
    }

    /** A function; OCaml sees it curried, and `() -> R` as `unit -> r`. */
    data class Fn(val params: List<Type>, val result: Type) : Type() {
        override fun toString() = render(this, mutableMapOf())
    }

    /** Follows bound variables to the representative type. */
    fun prune(): Type = if (this is Var) bound?.prune()?.also { bound = it } ?: this else this

    /** The type with every bound variable replaced by what it's bound to. */
    fun zonk(): Type = when (val t = prune()) {
        is Var -> t
        is Con -> Con(t.name, t.args.map { it.zonk() })
        is Fn -> Fn(t.params.map { it.zonk() }, t.result.zonk())
    }

    fun freeVars(into: MutableSet<Var> = linkedSetOf()): Set<Var> {
        when (val t = prune()) {
            is Var -> into += t
            is Con -> t.args.forEach { it.freeVars(into) }
            is Fn -> {
                t.params.forEach { it.freeVars(into) }
                t.result.freeVars(into)
            }
        }
        return into
    }

    /** Kotlin-style rendering for error messages. */
    override fun toString(): String = render(this, mutableMapOf())

    companion object {
        val INT = Con("Int")
        val FLOAT = Con("Float")
        val STRING = Con("String")
        val BOOLEAN = Con("Boolean")
        val UNIT = Con("Unit")
        val CHAR = Con("Char")
        fun list(element: Type) = Con("List", listOf(element))
        fun option(element: Type) = Con("Option", listOf(element))
        fun tuple(elements: List<Type>) = Con(TUPLE, elements)
        fun lazy(element: Type) = Con("Lazy", listOf(element))

        /** Tuples are a [Con] of this name with one argument per element. */
        const val TUPLE = "Tuple"

        fun render(type: Type, names: MutableMap<Var, String>): String = when (val t = type.prune()) {
            is Var -> names.getOrPut(t) { "T${names.size + 1}" }
            is Con -> if (t.name == TUPLE) "(${t.args.joinToString(", ") { render(it, names) }})"
            else if (t.args.isEmpty()) t.name else "${t.name}<${t.args.joinToString(", ") { render(it, names) }}>"
            is Fn -> "(${t.params.joinToString(", ") { render(it, names) }}) -> ${render(t.result, names)}"
        }
    }
}

/** `Double` is accepted as another name for `Float`, which is what OCaml calls it. */
val BUILTIN_TYPES = mapOf(
    "Int" to 0, "Float" to 0, "Double" to 0, "String" to 0, "Boolean" to 0, "Unit" to 0, "Char" to 0,
    "List" to 1, "Option" to 1, "Lazy" to 1,
)

class Scheme(val vars: List<Type.Var>, val type: Type) {
    companion object {
        fun mono(type: Type) = Scheme(emptyList(), type)
    }
}

class Unifier {
    private var nextId = 0

    fun fresh() = Type.Var(nextId++)

    fun instantiate(scheme: Scheme): Type {
        if (scheme.vars.isEmpty()) return scheme.type
        val mapping = scheme.vars.associateWith { fresh() as Type }
        return substitute(scheme.type, mapping)
    }

    fun substitute(type: Type, mapping: Map<Type.Var, Type>): Type = when (val t = type.prune()) {
        is Type.Var -> mapping[t] ?: t
        is Type.Con -> Type.Con(t.name, t.args.map { substitute(it, mapping) })
        is Type.Fn -> Type.Fn(t.params.map { substitute(it, mapping) }, substitute(t.result, mapping))
    }

    /** Makes [a] and [b] equal, or returns false (leaving partial bindings, which callers report). */
    fun unify(a: Type, b: Type): Boolean {
        val x = a.prune()
        val y = b.prune()
        return when {
            x === y -> true
            x is Type.Var -> bind(x, y)
            y is Type.Var -> bind(y, x)
            x is Type.Con && y is Type.Con ->
                x.name == y.name && x.args.size == y.args.size && x.args.zip(y.args).all { (p, q) -> unify(p, q) }
            x is Type.Fn && y is Type.Fn ->
                x.params.size == y.params.size &&
                    x.params.zip(y.params).all { (p, q) -> unify(p, q) } && unify(x.result, y.result)
            else -> false
        }
    }

    private fun bind(v: Type.Var, t: Type): Boolean {
        if (v in t.freeVars()) return false
        v.bound = t
        return true
    }
}

/**
 * How a built-in function or method is written in OCaml: [head] applied to [slots], where a slot
 * is the receiver, one of the call's arguments, or fixed OCaml text.
 */
sealed interface Builtin {
    data class Template(val head: String, val slots: List<Slot>) : Builtin

    /** `x.toString()` and string templates: rendered by the show function for the receiver's type. */
    data object Show : Builtin

    /** `List.joinToString(sep)`: shows each element, then concatenates. */
    data object JoinToString : Builtin

    sealed interface Slot {
        data object Receiver : Slot
        data class Arg(val index: Int) : Slot
        data class Raw(val text: String) : Slot
    }
}

private fun template(head: String, vararg slots: Any): Builtin = Builtin.Template(
    head,
    slots.map {
        when (it) {
            "recv" -> Builtin.Slot.Receiver
            is Int -> Builtin.Slot.Arg(it)
            is String -> Builtin.Slot.Raw(it)
            else -> error("bad slot $it")
        }
    },
)

/** A built-in method or property: its parameter and result types, given the receiver's. */
class BuiltinMember(val params: List<Type>, val result: Type, val translation: Builtin, val isProperty: Boolean = false)

object Builtins {
    /** Looks up `receiver.name`, where [receiver] is already pruned. */
    fun member(receiver: Type, name: String, u: Unifier): BuiltinMember? {
        if (name == "toString") return BuiltinMember(emptyList(), Type.STRING, Builtin.Show)
        if (receiver !is Type.Con) return null
        val t = Type
        return when (receiver.name) {
            "List" -> {
                val e = receiver.args[0]
                when (name) {
                    "map" -> u.fresh().let { r -> BuiltinMember(listOf(Type.Fn(listOf(e), r)), t.list(r), template("List.map", 0, "recv")) }
                    "filter" -> BuiltinMember(listOf(Type.Fn(listOf(e), t.BOOLEAN)), receiver, template("List.filter", 0, "recv"))
                    "flatMap" -> u.fresh().let { r -> BuiltinMember(listOf(Type.Fn(listOf(e), t.list(r))), t.list(r), template("List.concat_map", 0, "recv")) }
                    "fold" -> u.fresh().let { a -> BuiltinMember(listOf(a, Type.Fn(listOf(a, e), a)), a, template("List.fold_left", 1, 0, "recv")) }
                    "forEach" -> BuiltinMember(listOf(Type.Fn(listOf(e), t.UNIT)), t.UNIT, template("List.iter", 0, "recv"))
                    "any" -> BuiltinMember(listOf(Type.Fn(listOf(e), t.BOOLEAN)), t.BOOLEAN, template("List.exists", 0, "recv"))
                    "all" -> BuiltinMember(listOf(Type.Fn(listOf(e), t.BOOLEAN)), t.BOOLEAN, template("List.for_all", 0, "recv"))
                    "find" -> BuiltinMember(listOf(Type.Fn(listOf(e), t.BOOLEAN)), t.option(e), template("List.find_opt", 0, "recv"))
                    "contains" -> BuiltinMember(listOf(e), t.BOOLEAN, template("List.mem", 0, "recv"))
                    "reversed" -> BuiltinMember(emptyList(), receiver, template("List.rev", "recv"))
                    "isEmpty" -> BuiltinMember(emptyList(), t.BOOLEAN, template("List.is_empty", "recv"))
                    "plus" -> BuiltinMember(listOf(receiver), receiver, template("List.append", "recv", 0))
                    "firstOrNone" -> BuiltinMember(emptyList(), t.option(e), template("List.nth_opt", "recv", "0"))
                    "size" -> BuiltinMember(emptyList(), t.INT, template("List.length", "recv"), isProperty = true)
                    "joinToString" -> BuiltinMember(listOf(t.STRING), t.STRING, Builtin.JoinToString)
                    // [(key, value), ...].lookup(key): the value of the first pair with that key.
                    "lookup" -> (e.prune() as? Type.Con)?.takeIf { it.name == Type.TUPLE && it.args.size == 2 }?.let { pair ->
                        BuiltinMember(listOf(pair.args[0]), t.option(pair.args[1]), template("List.assoc_opt", 0, "recv"))
                    }
                    "sum" -> when (e.prune()) {
                        Type.INT -> BuiltinMember(emptyList(), t.INT, template("List.fold_left", "( + )", "0", "recv"))
                        Type.FLOAT -> BuiltinMember(emptyList(), t.FLOAT, template("List.fold_left", "( +. )", "0.", "recv"))
                        else -> null
                    }
                    else -> null
                }
            }
            "Option" -> {
                val e = receiver.args[0]
                when (name) {
                    "map" -> u.fresh().let { r -> BuiltinMember(listOf(Type.Fn(listOf(e), r)), t.option(r), template("Option.map", 0, "recv")) }
                    "flatMap" -> u.fresh().let { r -> BuiltinMember(listOf(Type.Fn(listOf(e), t.option(r))), t.option(r), template("Option.bind", "recv", 0)) }
                    "getOrElse" -> BuiltinMember(listOf(e), e, template("Option.value", "recv", "~default:", 0))
                    "isSome" -> BuiltinMember(emptyList(), t.BOOLEAN, template("Option.is_some", "recv"))
                    "isNone" -> BuiltinMember(emptyList(), t.BOOLEAN, template("Option.is_none", "recv"))
                    else -> null
                }
            }
            "Int" -> when (name) {
                "toFloat", "toDouble" -> BuiltinMember(emptyList(), t.FLOAT, template("float_of_int", "recv"))
                "coerceAtLeast" -> BuiltinMember(listOf(t.INT), t.INT, template("max", "recv", 0))
                "coerceAtMost" -> BuiltinMember(listOf(t.INT), t.INT, template("min", "recv", 0))
                else -> null
            }
            "Float" -> when (name) {
                "toInt" -> BuiltinMember(emptyList(), t.INT, template("int_of_float", "recv"))
                else -> null
            }
            "String" -> when (name) {
                "length" -> BuiltinMember(emptyList(), t.INT, template("String.length", "recv"), isProperty = true)
                "uppercase" -> BuiltinMember(emptyList(), t.STRING, template("String.uppercase_ascii", "recv"))
                "lowercase" -> BuiltinMember(emptyList(), t.STRING, template("String.lowercase_ascii", "recv"))
                "trim" -> BuiltinMember(emptyList(), t.STRING, template("String.trim", "recv"))
                "isEmpty" -> BuiltinMember(emptyList(), t.BOOLEAN, template("String.equal", "\"\"", "recv"))
                "startsWith" -> BuiltinMember(listOf(t.STRING), t.BOOLEAN, template("String.starts_with", "~prefix:", 0, "recv"))
                "toIntOrNone" -> BuiltinMember(emptyList(), t.option(t.INT), template("int_of_string_opt", "recv"))
                "toInt" -> BuiltinMember(emptyList(), t.INT, template("int_of_string", "recv"))
                "toFloat", "toDouble" -> BuiltinMember(emptyList(), t.FLOAT, template("float_of_string", "recv"))
                else -> null
            }
            "Char" -> when (name) {
                "code" -> BuiltinMember(emptyList(), t.INT, template("Char.code", "recv"), isProperty = true)
                else -> null
            }
            "Lazy" -> when (name) {
                "value" -> BuiltinMember(emptyList(), receiver.args[0], template("Lazy.force", "recv"), isProperty = true)
                "isInitialized" -> BuiltinMember(emptyList(), t.BOOLEAN, template("Lazy.is_val", "recv"))
                else -> null
            }
            else -> null
        }
    }

    private val MATH = setOf("sin", "cos", "tan", "asin", "acos", "atan", "sqrt", "exp", "log", "floor", "ceil")

    /** Top-level built-in functions: their type and OCaml name. */
    fun function(name: String, u: Unifier): Pair<Type, String>? = when (name) {
        "print" -> Type.Fn(listOf(Type.STRING), Type.UNIT) to "print_string"
        "println" -> Type.Fn(listOf(Type.STRING), Type.UNIT) to "print_endline"
        "max" -> u.fresh().let { a -> Type.Fn(listOf(a, a), a) to "max" }
        "min" -> u.fresh().let { a -> Type.Fn(listOf(a, a), a) to "min" }
        "abs" -> Type.Fn(listOf(Type.INT), Type.INT) to "abs"
        "identity" -> u.fresh().let { a -> Type.Fn(listOf(a), a) to "Fun.id" }
        "error" -> u.fresh().let { a -> Type.Fn(listOf(Type.STRING), a) to "failwith" }
        "atan2" -> Type.Fn(listOf(Type.FLOAT, Type.FLOAT), Type.FLOAT) to "atan2"
        // lazy(~e): computes e the first time `.value` is read, then remembers it.
        "lazy" -> u.fresh().let { a -> Type.Fn(listOf(Type.Fn(emptyList(), a)), Type.lazy(a)) to "Lazy.from_fun" }
        in MATH -> Type.Fn(listOf(Type.FLOAT), Type.FLOAT) to name
        else -> null
    }
}
