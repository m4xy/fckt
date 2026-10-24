package uk.m4xy.fckt.types

/** A type during and after inference. [Var]s are unification variables. */
sealed class Type {
    class Var(val id: Int) : Type() {
        var bound: Type? = null
    }

    data class Con(val name: String, val args: List<Type> = emptyList()) : Type() {
        override fun toString() = render(this, mutableMapOf())
    }

    /**
     * A function; OCaml sees it curried, and `() -> R` as `unit -> r`. Its [effects] are what
     * calling it may do: an [effects] row, or a variable for one. They're checked, then compiled
     * away, so two functions differing only in them are the same to OCaml, and [equals] ignores them.
     */
    class Fn(val params: List<Type>, val result: Type, val effects: Type = effects(emptySet(), null)) : Type() {
        override fun toString() = render(this, mutableMapOf())
        override fun equals(other: Any?) = other is Fn && params == other.params && result == other.result
        override fun hashCode() = params.hashCode() * 31 + result.hashCode()
    }

    /** Follows bound variables to the representative type. */
    fun prune(): Type = if (this is Var) bound?.prune()?.also { bound = it } ?: this else this

    /** The type with every bound variable replaced by what it's bound to. */
    fun zonk(): Type = when (val t = prune()) {
        is Var -> t
        is Con -> Con(t.name, t.args.map { it.zonk() })
        is Fn -> Fn(t.params.map { it.zonk() }, t.result.zonk(), t.effects.zonk())
    }

    fun freeVars(into: MutableSet<Var> = linkedSetOf()): Set<Var> {
        when (val t = prune()) {
            is Var -> into += t
            is Con -> t.args.forEach { it.freeVars(into) }
            is Fn -> {
                t.params.forEach { it.freeVars(into) }
                t.result.freeVars(into)
                t.effects.freeVars(into)
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

        /** `Result<A, E>`: OCaml's `('a, 'e) result`. */
        fun result(value: Type, error: Type) = Con("Result", listOf(value, error))
        fun tuple(elements: List<Type>) = Con(TUPLE, elements)
        fun lazy(element: Type) = Con("Lazy", listOf(element))

        /** Tuples are a [Con] of this name with one argument per element. */
        const val TUPLE = "Tuple"

        /**
         * Before a type's full name, a [Con] for it as seen outside a module whose spec makes it
         * abstract: there, nothing can look inside it.
         */
        const val ABSTRACT = "abstract "

        /** Before a spec's own type, while the spec says what a module must define. */
        const val SPEC_TYPE = "@"

        /** Before what an annotation's field can refer to: `Type`, `Spec`, `Module`, `Case`, `Field`, or `@` and an annotation. */
        const val META = "meta "

        /**
         * A row of effects is a [Con] of this name: the effects' full names, each a [Con] of no
         * arguments, then, if the row is open, a variable for the rest, which unification fills.
         */
        const val EFFECTS = "effects "

        /** The row of [labels], and, if open, of what [rest] turns out to be. */
        fun effects(labels: Set<String>, rest: Type?): Type = Con(EFFECTS, labels.sorted().map { Con(it) } + listOfNotNull(rest))

        /** A row's effects, and the variable for the rest if it's open, following what that's been bound to. */
        fun row(type: Type): Pair<Set<String>, Var?> {
            val labels = linkedSetOf<String>()
            var t = type.prune()
            while (true) {
                when (t) {
                    is Var -> return labels to t
                    is Con -> {
                        val rest = t.args.lastOrNull()?.prune()?.takeIf { it !is Con || it.name == EFFECTS }
                        t.args.filterIsInstance<Con>().filter { it.name != EFFECTS }.forEach { labels += it.name }
                        t = rest ?: return labels to null
                    }
                    is Fn -> return labels to null
                }
            }
        }

        /** A type's name in messages and output, without the markers above or a type parameter's `#`. */
        fun displayName(name: String) = name.removePrefix(META).removePrefix(ABSTRACT).removePrefix(SPEC_TYPE).removePrefix("#")

        fun render(type: Type, names: MutableMap<Var, String>): String = when (val t = type.prune()) {
            is Var -> names.getOrPut(t) { "T${names.size + 1}" }
            is Con -> if (t.name == TUPLE) "(${t.args.joinToString(", ") { render(it, names) }})"
            else if (t.args.isEmpty()) displayName(t.name) else "${displayName(t.name)}<${t.args.joinToString(", ") { render(it, names) }}>"
            is Fn -> row(t.effects).first.joinToString("") { "@${displayName(it).substringAfterLast('.')} " } +
                "(${t.params.joinToString(", ") { render(it, names) }}) -> ${render(t.result, names)}"
        }
    }
}

/** `Double` is accepted as another name for `Float`, which is what OCaml calls it. */
val BUILTIN_TYPES = mapOf(
    "Int" to 0, "Float" to 0, "Double" to 0, "String" to 0, "Boolean" to 0, "Unit" to 0, "Char" to 0,
    "List" to 1, "Option" to 1, "Lazy" to 1, "Result" to 2,
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
        is Type.Fn -> Type.Fn(t.params.map { substitute(it, mapping) }, substitute(t.result, mapping), substitute(t.effects, mapping))
    }

    /** Makes [a] and [b] equal, or returns false (leaving partial bindings, which callers report). */
    fun unify(a: Type, b: Type): Boolean {
        val x = a.prune()
        val y = b.prune()
        return when {
            x === y -> true
            x is Type.Con && y is Type.Con && (x.name == Type.EFFECTS || y.name == Type.EFFECTS) -> unifyEffects(x, y)
            x is Type.Var && y is Type.Con && y.name == Type.EFFECTS -> unifyEffects(x, y)
            y is Type.Var && x is Type.Con && x.name == Type.EFFECTS -> unifyEffects(x, y)
            x is Type.Var -> bind(x, y)
            y is Type.Var -> bind(y, x)
            x is Type.Con && y is Type.Con ->
                x.name == y.name && x.args.size == y.args.size && x.args.zip(y.args).all { (p, q) -> unify(p, q) }
            x is Type.Fn && y is Type.Fn ->
                x.params.size == y.params.size &&
                    x.params.zip(y.params).all { (p, q) -> unify(p, q) } && unify(x.result, y.result) && unify(x.effects, y.effects)
            else -> false
        }
    }

    /**
     * Makes two rows of effects the same set: what's in one but not the other must be in the
     * other's rest, so a closed row (no rest) can't take more. Two open rows share a new rest.
     */
    private fun unifyEffects(a: Type, b: Type): Boolean {
        val (x, restX) = Type.row(a)
        val (y, restY) = Type.row(b)
        val onlyX = x - y
        val onlyY = y - x
        if (restX != null && restX === restY) return onlyX.isEmpty() && onlyY.isEmpty()
        return when {
            restX == null && restY == null -> onlyX.isEmpty() && onlyY.isEmpty()
            restX == null -> onlyY.isEmpty() && bind(restY!!, Type.effects(onlyX, null))
            restY == null -> onlyX.isEmpty() && bind(restX, Type.effects(onlyY, null))
            else -> {
                val rest = fresh()
                bind(restX, Type.effects(onlyY, rest)) && bind(restY, Type.effects(onlyX, rest))
            }
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

/** Names of the assertion built-ins, which lowering translates specially. */
/** The annotation, declared in the prelude, that makes an annotation type an effect: `@Effect annotation type Console`. */
const val EFFECT = "Effect"

const val ASSERT = "assert"
const val ASSERT_EQUALS = "assertEquals"

object Builtins {
    /** Looks up `receiver.name`, where [receiver] is already pruned. */
    fun member(receiver: Type, name: String, u: Unifier): BuiltinMember? {
        if (name == "toString") return BuiltinMember(emptyList(), Type.STRING, Builtin.Show)
        // Everything else is in the prelude, as externals: std/prelude.fk.
        if (receiver is Type.Con && receiver.name == "List" && name == "joinToString") {
            return BuiltinMember(listOf(Type.STRING), Type.STRING, Builtin.JoinToString)
        }
        return null
    }

    /** Top-level built-in functions: their type and OCaml name. The rest are in the prelude. */
    fun function(name: String, u: Unifier): Pair<Type, String>? = when (name) {
        // Kotlin's Unit is both the type and its one value.
        "Unit" -> Type.UNIT to "()"
        // Lowering writes these out itself, to report the failing line.
        "assert" -> Type.Fn(listOf(Type.BOOLEAN), Type.UNIT, u.fresh()) to ASSERT
        "assertEquals" -> u.fresh().let { a -> Type.Fn(listOf(a, a), Type.UNIT, u.fresh()) to ASSERT_EQUALS }
        else -> null
    }
}
