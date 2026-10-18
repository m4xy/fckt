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
}

// ---- Declarations ----

data class FieldDef(val pos: Pos, val name: String, val type: TypeRef)

data class Param(val pos: Pos, val name: String, val type: TypeRef?)

sealed interface Decl {
    val pos: Pos
    val name: String

    /** `type Name<T>(a: A, b: B)`, or `type Name(A)`, whose one field is called `value`. */
    data class Record(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val fields: List<FieldDef>,
    ) : Decl

    /** `interface Name<T> { Variant(fields) ... }`: a sealed sum type. */
    data class Sealed(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val variants: List<VariantDef>,
    ) : Decl

    /** `name<T>(params): R = body`; [params] is null for a value (`name = body`). */
    data class Function(
        override val pos: Pos,
        override val name: String,
        val typeParams: List<String>,
        val params: List<Param>?,
        val returnType: TypeRef?,
        val body: Expr,
    ) : Decl
}

data class VariantDef(val pos: Pos, val name: String, val fields: List<FieldDef>)

data class SourceFile(val decls: List<Decl>)

// ---- Expressions ----
// The checker fills in [Expr.type] and, for nodes whose translation depends on types, a resolution.

sealed class Expr {
    abstract val pos: Pos
    var type: Type? = null

    class IntLit(override val pos: Pos, val value: Long) : Expr()
    class FloatLit(override val pos: Pos, val value: Double) : Expr()
    class BoolLit(override val pos: Pos, val value: Boolean) : Expr()
    class StringLit(override val pos: Pos, val parts: List<StringPart>) : Expr()
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

    /** `x -> e`, `(a, b) -> e`, or `~e` (no params). */
    class Lambda(override val pos: Pos, val params: List<Param>, val body: Expr) : Expr() {
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

    /** `Success(a, b)`, `None`, or (with [isCheck]) `is Success`, which smart-casts the subject. */
    data class Constructor(
        override val pos: Pos,
        val name: String,
        val args: List<Pattern>?,
        val isCheck: Boolean,
    ) : Pattern {
        var resolved: ConstructorRef? = null

        /** For `is Variant` on a variable: the OCaml name bound to the variant's inline record. */
        var castVar: String? = null
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
}

sealed interface MemberRef {
    /** A field of the record type [record], which lowering names when the label is ambiguous. */
    data class Field(val ocamlName: String, val record: String) : MemberRef

    /** A field of a variant the subject was smart-cast to; [recordVar] holds its inline record. */
    data class CastField(val recordVar: String, val ocamlName: String) : MemberRef

    data class Builtin(val method: uk.m4xy.fckt.types.Builtin) : MemberRef
}

/** How a binary operator was typed: the OCaml operator and which operands need int→float. */
data class BinaryRef(val ocamlOp: String, val promoteLeft: Boolean, val promoteRight: Boolean)
