package uk.m4xy.fckt.types

/** A type during and after inference. [Var]s are unification variables. */
sealed class Type {
    class Var(val id: Int) : Type() {
        var bound: Type? = null
    }

    /**
     * A named type applied to [args]. A type whose values can change, a record with a `var` field
     * or one that holds such a record, has a [region]: a variable standing for where its values
     * live, so that changing one is changing that region. Regions are only checked, so [equals]
     * ignores them.
     */
    data class Con(val name: String, val args: List<Type> = emptyList(), val region: Type? = null) : Type() {
        override fun toString() = render(this, mutableMapOf())
        override fun equals(other: Any?) = other is Con && name == other.name && args == other.args
        override fun hashCode() = name.hashCode() * 31 + args.hashCode()
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
        is Con -> Con(t.name, t.args.map { it.zonk() }, t.region?.zonk())
        is Fn -> Fn(t.params.map { it.zonk() }, t.result.zonk(), t.effects.zonk())
    }

    fun freeVars(into: MutableSet<Var> = linkedSetOf()): Set<Var> {
        when (val t = prune()) {
            is Var -> into += t
            is Con -> {
                t.args.forEach { it.freeVars(into) }
                t.region?.freeVars(into)
            }
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

        /** Before a handled effect's full name: a handler for it, `handler Console`, with the effect's type arguments and then what its answers do. */
        const val HANDLER = "handler "

        /** Before what an annotation's field can refer to: `Type`, `Spec`, `Module`, `Case`, `Field`, or `@` and an annotation. */
        const val META = "meta "

        /**
         * A row of effects is a [Con] of this name: the effects' full names, each a [Con] of no
         * arguments, then, if the row is open, a variable for the rest, which unification fills.
         */
        const val EFFECTS = "effects "

        /** The row of the effects named [labels], and, if open, of what [rest] turns out to be. */
        fun effects(labels: Set<String>, rest: Type?): Type = row(labels.sorted().map { Con(it) }, rest)

        /** The row of [labels], each an effect's name, with a region for `Mutating`, and of what [rest] turns out to be. */
        fun row(labels: List<Con>, rest: Type?): Type = Con(EFFECTS, labels + listOfNotNull(rest))

        /** Where the program's top-level values live, which outlive every function. */
        val GLOBAL = Con("global region")

        /**
         * Two of a row's effects are the same if they have the same name: a row has one of each, so a
         * generic effect's type arguments, `Yielding<Int>`, are one choice for the row. `Mutating` is
         * the exception: one for each region, as a function may change several values. A written
         * `@Mutating`, as in `@Mutating () -> Int`, doesn't say where, so it's any region.
         */
        fun sameEffect(a: Con, b: Con): Boolean {
            if (a.name != b.name) return false
            if (a.name != MUTATING_NAME) return true
            val x = a.args.firstOrNull()?.prune() ?: return true
            val y = b.args.firstOrNull()?.prune() ?: return true
            return x === y || (x is Con && y is Con && x.name == y.name)
        }

        /** [MUTATING], here where [sameEffect] can see it. */
        private const val MUTATING_NAME = "Mutating"

        /** A row's effects, once each, and the variable for the rest if it's open, following what that's been bound to. */
        fun row(type: Type): Pair<List<Con>, Var?> {
            val labels = mutableListOf<Con>()
            var t = type.prune()
            while (true) {
                when (t) {
                    is Var -> return labels to t
                    is Con -> {
                        val rest = t.args.lastOrNull()?.prune()?.takeIf { it !is Con || it.name == EFFECTS }
                        for (label in t.args.filterIsInstance<Con>()) if (label.name != EFFECTS && labels.none { sameEffect(it, label) }) labels += label
                        t = rest ?: return labels to null
                    }
                    is Fn -> return labels to null
                }
            }
        }

        /** The names of the effects in a row. */
        fun effectNames(type: Type): Set<String> = row(type).first.map { it.name }.toSet()

        /** A type's name in messages and output, without the markers above or a type parameter's `#`. */
        fun displayName(name: String) = name.removePrefix(META).removePrefix(ABSTRACT).removePrefix(SPEC_TYPE).removePrefix("#")

        fun render(type: Type, names: MutableMap<Var, String>): String = when (val t = type.prune()) {
            is Var -> names.getOrPut(t) { "T${names.size + 1}" }
            is Con -> if (t.name.startsWith(HANDLER)) "a handler for @${t.name.removePrefix(HANDLER).substringAfterLast('.')}"
            else if (t.name == TUPLE) "(${t.args.joinToString(", ") { render(it, names) }})"
            else if (t.args.isEmpty()) displayName(t.name) else "${displayName(t.name)}<${t.args.joinToString(", ") { render(it, names) }}>"
            is Fn -> effectNames(t.effects).sorted().joinToString("") { "@${displayName(it).substringAfterLast('.')} " } +
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
        is Type.Con -> Type.Con(t.name, t.args.map { substitute(it, mapping) }, t.region?.let { substitute(it, mapping) })
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
                x.name == y.name && x.args.size == y.args.size && x.args.zip(y.args).all { (p, q) -> unify(p, q) } &&
                    (x.region == null || y.region == null || unify(x.region, y.region))
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
        val onlyX = x.filter { e -> y.none { Type.sameEffect(it, e) } }
        val onlyY = y.filter { e -> x.none { Type.sameEffect(it, e) } }
        // The same generic effect in both: the same type arguments.
        for (e in x) {
            if (e.name == MUTATING || e.args.isEmpty()) continue
            val other = y.firstOrNull { it.name == e.name && it.args.size == e.args.size } ?: continue
            if (!e.args.zip(other.args).all { (p, q) -> unify(p, q) }) return false
        }
        if (restX != null && restX === restY) return onlyX.isEmpty() && onlyY.isEmpty()
        return when {
            restX == null && restY == null -> onlyX.isEmpty() && onlyY.isEmpty()
            restX == null -> onlyY.isEmpty() && bind(restY!!, Type.row(onlyX, null))
            restY == null -> onlyX.isEmpty() && bind(restX, Type.row(onlyY, null))
            else -> {
                val rest = fresh()
                bind(restX, Type.row(onlyY, rest)) && bind(restY, Type.row(onlyX, rest))
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

/** The effect, declared in the prelude, of assigning a `var`: changing a value someone else may see. */
const val MUTATING = "Mutating"

/** Prelude annotations: one that describes an annotation type, and isn't passed on to what that's used on; one that may be given more than once. */
const val META_ANNOTATION = "Meta"
const val REPEATABLE = "Repeatable"

/** On an annotation for specs, as `@Persistence @Encapsulates annotation type Repository`: what a module following such a spec does beyond what it says stays inside it. */
const val ENCAPSULATES = "Encapsulates"

/** On a handled effect: the module `main` uses to handle it when nothing else does, as `@HandledBy(Terminal) spec Console`. */
const val HANDLED_BY = "HandledBy"

/** The effect no operation has: a module or function that may only do it does nothing. */
const val PURE = "Pure"

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
