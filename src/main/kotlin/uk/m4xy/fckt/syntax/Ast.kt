package uk.m4xy.fckt.syntax

import uk.m4xy.fckt.types.Type

/** A place in the source; [file] is set when the program has more than one. */
data class Pos(val line: Int, val col: Int, val file: String? = null) {
    override fun toString() = (file?.let { "$it:" } ?: "") + "$line:$col"
}

open class CompileError(val pos: Pos?, message: String) : Exception(message) {
    /** Each error found: this one, or for [CompileErrors], all of them. */
    open val errors: List<CompileError> get() = listOf(this)

    override fun toString() = if (pos == null) "error: $message" else "$pos: error: $message"
}

/** Several errors, found by carrying on past each: reported in the order they're written. */
class CompileErrors private constructor(override val errors: List<CompileError>) : CompileError(errors.first().pos, errors.first().message!!) {
    override fun toString() = errors.joinToString("\n")

    companion object {
        /** Throws [errors], if there are any: the one, or all of them, in order. */
        fun throwIfAny(errors: List<CompileError>) {
            val all = errors.flatMap { it.errors }.distinctBy { it.toString() }
                .sortedWith(compareBy({ it.pos?.file ?: "" }, { it.pos?.line ?: 0 }, { it.pos?.col ?: 0 }))
            when (all.size) {
                0 -> {}
                1 -> throw all.single()
                else -> throw CompileErrors(all)
            }
        }
    }
}

// ---- Type syntax ----

sealed interface TypeRef {
    val pos: Pos

    /** `Name` or `Name<A, B>`. */
    data class Named(override val pos: Pos, val name: String, val args: List<TypeRef>) : TypeRef

    /** `(A, B) -> R`; `() -> R` has no params. With [effects], what calling it may do: `@Console (String) -> Unit`. */
    data class Function(override val pos: Pos, val params: List<TypeRef>, val result: TypeRef, val effects: List<Annotation> = emptyList()) : TypeRef

    /** `(A, B)`: a tuple. */
    data class Tuple(override val pos: Pos, val elements: List<TypeRef>) : TypeRef

    /** `@Console @Clock`, or an effect parameter's `@E`: a type argument that's effects, as in `Queue<@Console>`. */
    data class Effects(override val pos: Pos, val effects: List<Annotation>) : TypeRef
}

// ---- Declarations ----

/**
 * A field; a [isVar] one (`var balance: Int`) can be changed, which is `@Mutating`. With a
 * [default] (`version: String = "0.1.0"`), a constructor call can leave it out.
 */
data class FieldDef(
    val pos: Pos,
    val name: String,
    val type: TypeRef,
    val annotations: List<Annotation> = emptyList(),
    val isVar: Boolean = false,
    val default: Expr? = null,
    /** `vararg fixtures: Function`: an annotation's last field, a list of [type], given its items in order. */
    val isVararg: Boolean = false,
)

/** A parameter; with a [default] (`greeting: String = "hello"`), a call can leave it out. */
data class Param(val pos: Pos, val name: String, val type: TypeRef?, val annotations: List<Annotation> = emptyList(), val default: Expr? = null)

/**
 * A declaration. The parser gives each its own [name]; the checker renames them to their full
 * name, so `make` in `module Ratios` becomes `Ratios.make`.
 */
sealed interface Decl {
    val pos: Pos
    val name: String

    /** `private`: visible only inside the module that declares it. */
    val isPrivate: Boolean

    /** `opaque type`: outside its module, a type that can be named but not built or looked into. */
    val isOpaque: Boolean get() = false

    /** `@Name(...)` before it: checked, then compiled away. */
    val annotations: List<Annotation>

    /** The modules around it, from its full name: `Ratios.make` is in `[Ratios]`. */
    val path: List<String> get() = name.split('.').dropLast(1)
    val simpleName: String get() = name.substringAfterLast('.')

    /**
     * `type Name<T>(a: A, b: B)`, or `type Name(A)`, whose one field is called `value`. With
     * [privateConstructor] (`type Name private(...)`), only its module can build one. An
     * [isAnnotation] one (`annotation type Name(...)`) is written `@Name(...)` on declarations,
     * and exists only while checking: no value has its type. An [external] one (`external type
     * Array<A> = "'a array"`) is a type OCaml implements, written in OCaml with each type parameter
     * `P` as `'p`; it has no fields, and only its functions make one.
     */
    data class Record(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val fields: List<FieldDef>,
        override val isPrivate: Boolean = false,
        val privateConstructor: Boolean = false,
        override val isOpaque: Boolean = false,
        override val annotations: List<Annotation> = emptyList(),
        val isAnnotation: Boolean = false,
        val external: String? = null,
    ) : Decl

    /**
     * `type Name<T> { Variant(fields) ... }`: a type with cases, a sealed sum type. Besides
     * declaring its own [variants], it can take in other types' cases: one at a time with `Other.Case`
     * ([caseRefs]), or all of them with `*Other<T>` ([includes]).
     */
    data class Sealed(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val variants: List<VariantDef>,
        val caseRefs: List<CaseRefDef> = emptyList(),
        val includes: List<TypeRef.Named> = emptyList(),
        override val isPrivate: Boolean = false,
        override val isOpaque: Boolean = false,
        override val annotations: List<Annotation> = emptyList(),
    ) : Decl

    /** `type Name<T> = Other<T>`: another name for a type. */
    data class Alias(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val target: TypeRef,
        override val isPrivate: Boolean = false,
        override val isOpaque: Boolean = false,
        override val annotations: List<Annotation> = emptyList(),
    ) : Decl

    /**
     * `name<T>(params): R = body`; [params] is null for a value (`name = body`).
     *
     * An [extension] (`List<A>.second(): Option<A> = ...`) is called as a method, chosen by its
     * receiver's type; the receiver is its first parameter, `this`, and its name is mangled with
     * the receiver's (`List$second`) so several types can each have one. Without parentheses
     * (`String.length: Int = ...`) it's a [property]. An [external] one's body is OCaml:
     * a function applied to its parameters, by name (`"List.map f this"`).
     */
    data class Function(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val params: List<Param>?,
        val returnType: TypeRef?,
        val body: Expr,
        override val isPrivate: Boolean = false,
        val extension: Boolean = false,
        val property: Boolean = false,
        val external: String? = null,
        override val annotations: List<Annotation> = emptyList(),
    ) : Decl {
        /** The name it's called by: `second` for `List$second`. */
        val methodName: String get() = simpleName.substringAfter('$')
    }

    /**
     * `module Name { declarations }`: a namespace whose `private` members stay inside it, and whose
     * `opaque` types are abstract outside it. With a [spec] (`module Name : Spec { ... }`), it must
     * define what the spec lists, with the spec's types; the spec's type arguments, if any, say
     * what its types are (`module IntOrder : Ordered<Int>`). With [params] (`module Name(M: Spec) { ... }`) it's a
     * functor, which makes a module out of others. With an [application]
     * (`module Name = Functor(Arg)`), it's the module a functor makes from [Arg].
     */
    data class Module(
        override val pos: Pos,
        override val name: String,
        val decls: List<Decl>,
        override val isPrivate: Boolean = false,
        val spec: TypeRef.Named? = null,
        val params: List<ModuleParam> = emptyList(),
        val application: Application? = null,
        override val annotations: List<Annotation> = emptyList(),
        /** A package: the declarations of a folder's files, named after the folder. */
        val isPackage: Boolean = false,
        /**
         * `module Results<E> : Monad`: types its declarations are generic in, as each of its
         * functions takes them as type parameters, and its type aliases use them, `type M<A> =
         * Result<A, E>`, meaning the E of whatever uses them.
         */
        val typeParams: List<String> = emptyList(),
        /**
         * For a package a library compiled already, the OCaml module it's in, `Uk_m4xy_units`: its
         * declarations come from the library's interface, and the OCaml refers to that module's.
         */
        val compiled: String? = null,
    ) : Decl

    /**
     * `spec Name { type T; f(x: T): Int; v: T }`: what a module must define. Its [types] are
     * abstract: modules choose what each is, and outside them it's hidden.
     */
    data class Spec(
        override val pos: Pos,
        override val name: String,
        val types: List<SpecType>,
        val values: List<SpecValue>,
        override val isPrivate: Boolean = false,
        override val annotations: List<Annotation> = emptyList(),
        val typeParams: List<String> = emptyList(),
        /** `effect OrdersDb = Database`: a separate effect with the same operations, which linking fills in. */
        val copyOf: TypeRef.Named? = null,
        /**
         * `spec Monad : Applicative`: the specs it builds on, whose types and values it has too, which
         * linking fills in. A module that follows it can be given where any of them is wanted.
         */
        val parents: List<TypeRef.Named> = emptyList(),
    ) : Decl
}

/**
 * `M: Spec` in a functor's `(...)`: a module it takes; with `= Standard`, the module it's given when
 * an application leaves it out, named where the functor is declared.
 */
data class ModuleParam(val pos: Pos, val name: String, val spec: TypeRef.Named, val default: TypeRef.Named? = null)

/**
 * `SortedSet(IntOrder)`, `Plugin(Reporting = Quiet)`: a functor given modules, in order, then by the
 * names of the parameters they're for, with those left out given their defaults.
 */
data class Application(val functor: TypeRef.Named, val args: List<ModuleArg>)

/** A module given to a functor: for its next parameter, or for the one [name]d. */
data class ModuleArg(val name: String?, val module: TypeRef.Named)

/** `type Name<A>` in a spec. */
data class SpecType(val pos: Pos, val name: String, val typeParams: List<String>)

/**
 * `name<A>(params): R` or `name: T` in a spec; [params] is null for a value. With a [default]
 * (`map(...): M<B> = bind(m, ...)`), a module that follows the spec and doesn't define it has
 * this, which linking copies in.
 */
data class SpecValue(
    val pos: Pos,
    val name: String,
    val typeParams: List<String>,
    val params: List<Param>?,
    val type: TypeRef,
    val annotations: List<Annotation> = emptyList(),
    val default: Expr? = null,
)

data class VariantDef(val pos: Pos, val name: String, val fields: List<FieldDef>, val annotations: List<Annotation> = emptyList())

/** `Owner.Case` in a type's cases: a case declared by another type. [owner] may be qualified: `Letters.Alphabet`. */
data class CaseRefDef(val pos: Pos, val owner: String, val case: String)

/**
 * A program's declarations, with each file's imports, by its path: null for the program's own
 * file. A file's [Import]s name what it uses from other packages.
 */
data class SourceFile(val decls: List<Decl>, val imports: Map<String?, List<Import>> = emptyMap())

/**
 * `import geometry.Shapes`, `import geometry.Shapes.area as size`, or `import geometry.*`
 * ([wildcard]): what a file uses from another package, by its full name, [path].
 */
data class Import(val pos: Pos, val path: List<String>, val wildcard: Boolean = false, val alias: String? = null) {
    /** The name it's known by in the file, unless it's a wildcard. */
    val name get() = alias ?: path.last()
}

// ---- Annotations ----

/**
 * `@Name` or `@Name(args)`, on a declaration, a field, a case or a parameter. Its arguments are
 * constants and references, never calculations, so a tool can read them without running anything;
 * the checker resolves each against the annotation's declaration, and then it's compiled away.
 */
class Annotation(val pos: Pos, val name: String, val args: List<MetaArg>, val typeArgs: List<TypeRef> = emptyList()) {
    /** Its declaration, set by the checker: an annotation type, or a handled effect's spec. */
    var decl: Decl? = null
}

/** An annotation's argument, by position or by [name]. */
data class MetaArg(val name: String?, val value: Meta)

/**
 * What an annotation's argument can be: a constant, a reference, or a list or tuple of them. The
 * checker sets what it [resolved] to.
 */
sealed class Meta {
    abstract val pos: Pos
    var resolved: MetaRef? = null

    /** A number, string, character or Boolean. */
    class Literal(val expr: Expr) : Meta() {
        override val pos get() = expr.pos
    }

    /**
     * A name, maybe qualified: a function, value, case, field, module, spec or type. With
     * [typeArgs], a type, `List<User>`; with [args], a case, record or annotation made of constants.
     */
    class Ref(override val pos: Pos, val name: String, val typeArgs: List<TypeRef>?, val args: List<MetaArg>?) : Meta()

    class ListOf(override val pos: Pos, val items: List<Meta>) : Meta()
    class TupleOf(override val pos: Pos, val items: List<Meta>) : Meta()
}

/** What a [Meta] refers to, set by the checker. */
sealed interface MetaRef {
    /** An ordinary value, checked as [expr]: a constant, a function or value by name, or a case or record made of constants. */
    data class Value(val expr: Expr) : MetaRef
    data class Type(val type: uk.m4xy.fckt.types.Type) : MetaRef
    data class Spec(val spec: Decl.Spec) : MetaRef
    data class Module(val module: Decl.Module) : MetaRef
    data class Function(val function: Decl.Function) : MetaRef
    data class Case(val sealed: Decl.Sealed, val variant: VariantDef) : MetaRef
    data class Field(val record: Decl.Record, val field: FieldDef) : MetaRef
    data class Annotation(val annotation: uk.m4xy.fckt.syntax.Annotation) : MetaRef
}

// ---- Expressions ----
// The checker fills in [Expr.type] and, for nodes whose translation depends on types, a resolution.

sealed class Expr {
    abstract val pos: Pos
    var type: Type? = null

    /** Set when the value is used as a larger sealed type than its own, e.g. a Vowel as an Alphabet. */
    var coerceTo: Type? = null

    /**
     * The body of a declaration in a library's interface, or a default value's there: compiled
     * already, so what it is is what the declaration says.
     */
    class Compiled(override val pos: Pos) : Expr()

    class IntLit(override val pos: Pos, val value: Long) : Expr()
    class FloatLit(override val pos: Pos, val value: Double) : Expr()
    class BoolLit(override val pos: Pos, val value: Boolean) : Expr()
    class StringLit(override val pos: Pos, val parts: List<StringPart>) : Expr()
    /** `'a'`: one character, a Unicode code point, [value]. */
    class CharLit(override val pos: Pos, val value: Int) : Expr() {
        /** The character as text. */
        val text: String get() = String(Character.toChars(value))
    }
    class Tuple(override val pos: Pos, val elements: List<Expr>) : Expr()

    /** `*xs` inside a list literal: all of the list `xs`. */
    class Spread(override val pos: Pos, val list: Expr) : Expr()
    class Name(override val pos: Pos, val name: String) : Expr() {
        var resolved: NameRef? = null
    }
    class ListLit(override val pos: Pos, val items: List<Expr>) : Expr()

    /** `callee(args)`; arguments may be named (`f(x = 1)`). */
    /** `f(x)`, or with [typeArgs], the types for those `f` declares: `emptyMap<String, Int>()`. */
    class Call(override val pos: Pos, val callee: Expr, args: List<Arg>, val typeArgs: List<TypeRef> = emptyList()) : Expr() {
        /** As written, then with a call to its default for each parameter left out that has one. */
        var args: List<Arg> = args

        var resolved: CallRef? = null

        /** Gives Nothing: it never returns, so whatever type is wanted where it is. */
        var absurd = false
    }

    /** `target.name`, which may turn out to be a field or, under a [Call], a method. */
    class Member(override val pos: Pos, val target: Expr, val name: String) : Expr() {
        var resolved: MemberRef? = null
    }

    class Binary(override val pos: Pos, val op: String, val left: Expr, val right: Expr) : Expr() {
        var resolved: BinaryRef? = null
    }

    class Unary(override val pos: Pos, val op: String, val operand: Expr) : Expr()

    /**
     * `target = value`, as a statement: changes a `var` local or a record's `var` field. `x += 1`
     * is `x = x + 1`.
     */
    class Assign(override val pos: Pos, val target: Expr, val value: Expr) : Expr()

    /**
     * `x -> e`, `(a, b) -> e`, or `~e`, which [ignoresArguments]: it takes as many as the expected
     * function type has (`~true` as a `(String, Money) -> Boolean`), or none, making a thunk.
     */
    class Lambda(
        override val pos: Pos,
        val params: List<Param>,
        val body: Expr,
        val ignoresArguments: Boolean = false,
    ) : Expr() {
        var paramNames: List<String> = emptyList()
    }

    class Pipe(override val pos: Pos, val value: Expr, val function: Expr) : Expr() {
        /** `x |> f(a)` where `f` takes one more argument: becomes `f(a, x)`. */
        var partial = false
    }

    /** `if (c) a else b`; without [hasElse], `if (c) a` is a statement, with `Unit` for [otherwise]. */
    class If(override val pos: Pos, val condition: Expr, val then: Expr, val otherwise: Expr, val hasElse: Boolean = true) : Expr()

    /** `when (subject) { ... }`, or `when { cond -> ... }` when [subject] is null. */
    class When(override val pos: Pos, val subject: Expr?, val branches: List<Branch>) : Expr() {
        /** Set when a constructor in the patterns needs the subject's type to tell it apart. */
        var annotateSubject = false
    }

    /**
     * `{ statements; result }`. A block that ends with `x = value` may be changing a var, which
     * the parser can't tell from a binding: then [result] is `Unit`, and the checker makes sure
     * [endsWith] did change one.
     */
    class Block(override val pos: Pos, val statements: List<Statement>, val result: Expr, val endsWith: Statement.Binding? = null) : Expr()

    /**
     * `Options { x by half(n); x + 1 }`: a block whose `by` lines go through [module]'s `bind`, and
     * whose result goes through its `pure`. [module] is a name or a path, like `Shapes.Options`.
     * Without a bind, the `by` lines are paired with `product` and the result made with `map`.
     */
    class Monadic(override val pos: Pos, val module: Expr, val block: Block) : Expr() {
        /**
         * The module's functions, set by the checker: [bind] and [pure], or, for a module without
         * bind, [map] and [product], which run the `by` lines independently of each other.
         */
        var bind: Decl.Function? = null
        var pure: Decl.Function? = null
        var map: Decl.Function? = null
        var product: Decl.Function? = null

        /** Set instead when [module] follows a handled effect's spec: the block runs with it handling that effect. */
        var handles: Decl.Spec? = null
        var handler: Decl.Module? = null
    }

    /**
     * `handler { block }`: runs [block] with [handler], a value made from a handled effect's spec,
     * as `Console(println = ...)`, answering that effect's operations.
     */
    class Handle(override val pos: Pos, val handler: Expr, val block: Block) : Expr() {
        var handles: Decl.Spec? = null

        /** Set instead when [handler] is a function: `sequence { ... }` is `sequence(~{ ... })`. */
        var call: Call? = null
    }
}

sealed interface StringPart {
    data class Text(val text: String) : StringPart
    data class Interpolated(val expr: Expr) : StringPart
}

data class Arg(val name: String?, val value: Expr)

sealed interface Statement {
    val pos: Pos

    /**
     * `name = value`, or a local function `name(params) = value`. With [isVar] (`var name = value`),
     * a local that can be changed; `name = value` for a `var` already in scope changes it, which the
     * checker marks as [assigns].
     */
    data class Binding(
        override val pos: Pos,
        val name: String,
        val params: List<Param>?,
        val returnType: TypeRef?,
        val value: Expr,
        val isVar: Boolean = false,
    ) : Statement {
        var assigns = false

        /** OCaml names of the binding and its parameters, set by the checker. */
        var ocamlName: String = ""
        var paramNames: List<String> = emptyList()
        var recursive = false
    }

    data class Effect(override val pos: Pos, val expr: Expr) : Statement

    /** `(a, b) = value`: takes a tuple, or a record's fields in order, apart. */
    data class Destructure(override val pos: Pos, val pattern: Pattern, val value: Expr) : Statement

    /** `x by value`, in a [Expr.Monadic] block: the rest of the block runs with what [value] holds. */
    data class Bind(override val pos: Pos, val pattern: Pattern, val value: Expr) : Statement
}

/**
 * A `when` arm. With a subject, [patterns] are matched against it (several mean "any of");
 * without one, [condition] is tested. `else` has neither.
 */
/**
 * A `when` branch: [patterns] the subject may match, or a [condition] in a `when` without one, or
 * neither for `else`. A [guard], `Some(n) if n > 0 ->`, must hold too, with the patterns' names.
 */
data class Branch(val pos: Pos, val patterns: List<Pattern>, val condition: Expr?, val body: Expr, val guard: Expr? = null)

sealed interface Pattern {
    val pos: Pos

    data class Wildcard(override val pos: Pos) : Pattern
    data class Bind(override val pos: Pos, val name: String) : Pattern {
        var ocamlName: String = ""
    }
    data class Literal(override val pos: Pos, val expr: Expr) : Pattern

    /** `(p, q)`: a tuple, or (by position) a record's fields. */
    data class Tuple(override val pos: Pos, val elements: List<Pattern>) : Pattern {
        var record: Decl.Record? = null
    }

    /** `[]`, `[a, b]`, or `[head, *tail]` when [rest] is set. */
    data class ListPattern(override val pos: Pos, val elements: List<Pattern>, val rest: Pattern?) : Pattern

    /**
     * `Success(a, b)`, `Alphabet.A`, `None`, or (with [isCheck]) `is Success`, which smart-casts the
     * subject. `is Vowel`, naming a sealed type, narrows the subject to that sealed type's cases.
     */
    data class Constructor(
        override val pos: Pos,
        val name: String,
        val args: List<Pattern>?,
        val isCheck: Boolean,
        val qualifier: String? = null,
    ) : Pattern {
        var resolved: ConstructorRef? = null

        /** For `is Variant` on a variable: the OCaml name bound to the variant's inline record. */
        var castVar: String? = null

        /** For `is Variant` on a variable, when the variant is a tag: the names bound to its fields. */
        var castFieldVars: List<String>? = null

        /** For `is Sealed type`: the sealed type narrowed to, and the variable to rebind, if any. */
        var narrowTo: Decl.Sealed? = null
        var narrowVar: String? = null
    }
}

// ---- Resolutions, set by the checker and read by lowering ----

sealed interface NameRef {
    /** A local or parameter; a [isVar] one is an OCaml `ref`. */
    data class Local(val ocamlName: String, val isVar: Boolean = false) : NameRef
    data class Global(val decl: Decl.Function) : NameRef

    /** An operation of the handled effect [spec], as `println` is of Console: calling it performs the effect. */
    data class Operation(val spec: Decl.Spec, val operation: SpecValue) : NameRef

    /** A module following a handled effect's spec, used as a value: a handler. */
    data class Handler(val module: Decl.Module, val spec: Decl.Spec) : NameRef

    /** A module used as a value of the spec it follows, as `Quiet` is a `Reporting`. */
    data class Module(val module: Decl.Module, val spec: Decl.Spec) : NameRef

    /** A record type or variant used as a value, e.g. as `Money` in `items.map(Money)`. */
    data class Constructor(val ref: ConstructorRef) : NameRef
    data class Builtin(val ocaml: String) : NameRef
}

sealed interface ConstructorRef {
    val name: String

    data class Record(val decl: Decl.Record) : ConstructorRef {
        override val name get() = decl.name
    }

    data class Variant(val sealed: Decl.Sealed?, val variant: VariantDef?, override val name: String) : ConstructorRef
}

sealed interface CallRef {
    /** An ordinary call; [order] maps each declared parameter to the index of its argument. */
    data class Function(val order: List<Int>) : CallRef

    /** Construction of a record or variant, arguments in field order. */
    data class Construct(val ref: ConstructorRef, val order: List<Int>) : CallRef

    /** A call to a function that isn't a member, written as `receiver.f(args)`. */
    data class Extension(val decl: Decl.Function) : CallRef

    /** A built-in method or function, with its translation. */
    data class Builtin(val method: uk.m4xy.fckt.types.Builtin) : CallRef

    /**
     * `Console(println = ..., readLine = ...)`: a handler for [spec], from its operations' answers, in
     * spec order; each that [resumes] takes `resume`, and the others are resumed with what they give.
     */
    data class Handler(val spec: Decl.Spec, val order: List<Int>, val resumes: List<Boolean>) : CallRef

    /** `record.copy(field = value, ...)`: a copy with some fields replaced. */
    data class Copy(val record: Decl.Record) : CallRef

    /** `FkTests.Plugin(Reporting = Quiet)`: a functor's module, made beside the call, as a value of the functor's spec. */
    data class Module(val module: Decl.Module, val spec: Decl.Spec) : CallRef
}

sealed interface MemberRef {
    /** A field of the record type [record], which lowering names when the label is ambiguous. */
    data class Field(val ocamlName: String, val record: String) : MemberRef

    /** `s.length`, where `String.length: Int = ...` is an extension property. */
    data class Extension(val decl: Decl.Function) : MemberRef

    /** A field of a variant the subject was smart-cast to; [recordVar] holds its inline record. */
    data class CastField(val recordVar: String, val ocamlName: String) : MemberRef

    /** A field of a tag the subject was smart-cast to, bound directly to [variable]. */
    data class CastTagField(val variable: String) : MemberRef

    /** `Ratios.make`: a function or value named through its module. */
    data class Global(val decl: Decl.Function) : MemberRef

    /** `Console.println`: an operation named through its effect. */
    data class Operation(val spec: Decl.Spec, val operation: SpecValue) : MemberRef

    /** `Alphabet.A`: a case named through its sealed type. */
    data class Case(val ref: ConstructorRef) : MemberRef

    /** `reporting.Quiet`: a module named through its package, as a value of the spec it follows. */
    data class Module(val module: Decl.Module, val spec: Decl.Spec) : MemberRef

    /** `plugin.tasks`: what a module given as a value of [spec] has for its [value]. */
    data class OfModule(val spec: Decl.Spec, val value: SpecValue) : MemberRef

    data class Builtin(val method: uk.m4xy.fckt.types.Builtin) : MemberRef
}

/** How a binary operator was typed: the OCaml operator and which operands need int→float. */
data class BinaryRef(val ocamlOp: String, val promoteLeft: Boolean, val promoteRight: Boolean)
