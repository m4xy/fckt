package uk.m4xy.fckt.syntax

import uk.m4xy.fckt.types.Type

data class Pos(val line: Int, val col: Int) {
    override fun toString() = "$line:$col"
}

class CompileError(val pos: Pos?, message: String) : Exception(message) {
    override fun toString() = if (pos == null) "error: $message" else "$pos: error: $message"
}

// ---- Type syntax ----

sealed interface TypeRef {
    val pos: Pos

    /** `Name` or `Name<A, B>`. */
    data class Named(override val pos: Pos, val name: String, val args: List<TypeRef>) : TypeRef

    /** `(A, B) -> R`; `() -> R` has no params. */
    data class Function(override val pos: Pos, val params: List<TypeRef>, val result: TypeRef) : TypeRef

    /** `(A, B)`: a tuple. */
    data class Tuple(override val pos: Pos, val elements: List<TypeRef>) : TypeRef
}

// ---- Declarations ----

data class FieldDef(val pos: Pos, val name: String, val type: TypeRef)

data class Param(val pos: Pos, val name: String, val type: TypeRef?)

/**
 * A declaration. The parser gives each its own [name]; the checker renames them to their full
 * name, so `make` in `module Ratios` becomes `Ratios.make`.
 */
sealed interface Decl {
    val pos: Pos
    val name: String

    /** `private`: visible only inside the module that declares it. */
    val isPrivate: Boolean

    /** The modules around it, from its full name: `Ratios.make` is in `[Ratios]`. */
    val path: List<String> get() = name.split('.').dropLast(1)
    val simpleName: String get() = name.substringAfterLast('.')

    /**
     * `type Name<T>(a: A, b: B)`, or `type Name(A)`, whose one field is called `value`. With
     * [privateConstructor] (`type Name private(...)`), only its module can build one.
     */
    data class Record(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val fields: List<FieldDef>,
        override val isPrivate: Boolean = false,
        val privateConstructor: Boolean = false,
    ) : Decl

    /**
     * `interface Name<T> { Variant(fields) ... }`: a sealed sum type. Besides declaring its own
     * [variants], it can take in other interfaces' cases: one at a time with `Other.Case`
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
    ) : Decl

    /** `name<T>(params): R = body`; [params] is null for a value (`name = body`). */
    data class Function(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val params: List<Param>?,
        val returnType: TypeRef?,
        val body: Expr,
        override val isPrivate: Boolean = false,
    ) : Decl

    /** `module Name { declarations }`: a namespace whose `private` members stay inside it. */
    data class Module(
        override val pos: Pos,
        override val name: String,
        val decls: List<Decl>,
        override val isPrivate: Boolean = false,
    ) : Decl
}

data class VariantDef(val pos: Pos, val name: String, val fields: List<FieldDef>)

/** `Owner.Case` in an interface body: a case declared by another interface. [owner] may be qualified: `Letters.Alphabet`. */
data class CaseRefDef(val pos: Pos, val owner: String, val case: String)

data class SourceFile(val decls: List<Decl>)

// ---- Expressions ----
// The checker fills in [Expr.type] and, for nodes whose translation depends on types, a resolution.

sealed class Expr {
    abstract val pos: Pos
    var type: Type? = null

    /** Set when the value is used as a larger interface than its own, e.g. a Vowel as an Alphabet. */
    var coerceTo: Type? = null

    class IntLit(override val pos: Pos, val value: Long) : Expr()
    class FloatLit(override val pos: Pos, val value: Double) : Expr()
    class BoolLit(override val pos: Pos, val value: Boolean) : Expr()
    class StringLit(override val pos: Pos, val parts: List<StringPart>) : Expr()
    class CharLit(override val pos: Pos, val value: Char) : Expr()
    class Tuple(override val pos: Pos, val elements: List<Expr>) : Expr()

    /** `*xs` inside a list literal: all of the list `xs`. */
    class Spread(override val pos: Pos, val list: Expr) : Expr()
    class Name(override val pos: Pos, val name: String) : Expr() {
        var resolved: NameRef? = null
    }
    class ListLit(override val pos: Pos, val items: List<Expr>) : Expr()

    /** `callee(args)`; arguments may be named (`f(x = 1)`). */
    class Call(override val pos: Pos, val callee: Expr, val args: List<Arg>) : Expr() {
        var resolved: CallRef? = null
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

    class If(override val pos: Pos, val condition: Expr, val then: Expr, val otherwise: Expr) : Expr()

    /** `when (subject) { ... }`, or `when { cond -> ... }` when [subject] is null. */
    class When(override val pos: Pos, val subject: Expr?, val branches: List<Branch>) : Expr() {
        /** Set when a constructor in the patterns needs the subject's type to tell it apart. */
        var annotateSubject = false
    }

    class Block(override val pos: Pos, val statements: List<Statement>, val result: Expr) : Expr()
}

sealed interface StringPart {
    data class Text(val text: String) : StringPart
    data class Interpolated(val expr: Expr) : StringPart
}

data class Arg(val name: String?, val value: Expr)

sealed interface Statement {
    val pos: Pos

    /** `name = value`, or a local function `name(params) = value`. */
    data class Binding(
        override val pos: Pos,
        val name: String,
        val params: List<Param>?,
        val returnType: TypeRef?,
        val value: Expr,
    ) : Statement {
        /** OCaml names of the binding and its parameters, set by the checker. */
        var ocamlName: String = ""
        var paramNames: List<String> = emptyList()
        var recursive = false
    }

    data class Effect(override val pos: Pos, val expr: Expr) : Statement

    /** `(a, b) = value`: takes a tuple, or a record's fields in order, apart. */
    data class Destructure(override val pos: Pos, val pattern: Pattern, val value: Expr) : Statement
}

/**
 * A `when` arm. With a subject, [patterns] are matched against it (several mean "any of");
 * without one, [condition] is tested. `else` has neither.
 */
data class Branch(val pos: Pos, val patterns: List<Pattern>, val condition: Expr?, val body: Expr)

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
     * subject. `is Vowel`, naming an interface, narrows the subject to that interface's cases.
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

        /** For `is Interface`: the interface narrowed to, and the variable to rebind, if any. */
        var narrowTo: Decl.Sealed? = null
        var narrowVar: String? = null
    }
}

// ---- Resolutions, set by the checker and read by lowering ----

sealed interface NameRef {
    data class Local(val ocamlName: String) : NameRef
    data class Global(val decl: Decl.Function) : NameRef

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

    /** `record.copy(field = value, ...)`: a copy with some fields replaced. */
    data class Copy(val record: Decl.Record) : CallRef
}

sealed interface MemberRef {
    /** A field of the record type [record], which lowering names when the label is ambiguous. */
    data class Field(val ocamlName: String, val record: String) : MemberRef

    /** A field of a variant the subject was smart-cast to; [recordVar] holds its inline record. */
    data class CastField(val recordVar: String, val ocamlName: String) : MemberRef

    /** A field of a tag the subject was smart-cast to, bound directly to [variable]. */
    data class CastTagField(val variable: String) : MemberRef

    /** `Ratios.make`: a function or value named through its module. */
    data class Global(val decl: Decl.Function) : MemberRef

    /** `Alphabet.A`: a case named through its interface. */
    data class Case(val ref: ConstructorRef) : MemberRef

    data class Builtin(val method: uk.m4xy.fckt.types.Builtin) : MemberRef
}

/** How a binary operator was typed: the OCaml operator and which operands need int→float. */
data class BinaryRef(val ocamlOp: String, val promoteLeft: Boolean, val promoteRight: Boolean)
