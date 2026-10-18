package uk.m4xy.fckt.ocaml

import uk.m4xy.kastor.Children
import uk.m4xy.kastor.Element
import uk.m4xy.kastor.Required

// The OCaml output tree, described for Kastor. Children are positional: each element's KDoc says
// what its children are, in order. Types and patterns are already-rendered OCaml text.

// ---- Structure ----

/** A compilation unit (`.ml` file): type groups, let definitions and modules, in order. */
@Element("structure", function = "structure")
@Children(TypeGroup::class, LetGroup::class, Comment::class, ModuleDef::class)
interface Structure

/**
 * `module Name [: sig ... end] = struct ... end`. Children: an optional [Signature], then the
 * module's items.
 */
@Element("module", function = "moduleDef")
@Children(Signature::class, TypeGroup::class, LetGroup::class, Comment::class, ModuleDef::class)
interface ModuleDef {
    @Required val name: String
}

/** `sig ... end`: what a module shows outside it. */
@Element("sig", function = "signature")
@Children(TypeGroup::class, SigValue::class, SigModule::class)
interface Signature

/** `val name : type` in a signature. */
@Element("sig-value", function = "sigValue")
interface SigValue {
    @Required val name: String
    @Required val type: String
}

/** `module Name : sig ... end` inside a signature. */
@Element("sig-module", function = "sigModule")
@Children(TypeGroup::class, SigValue::class, SigModule::class)
interface SigModule {
    @Required val name: String
}

/** `(* text *)` */
@Element("comment", function = "comment")
interface Comment {
    @Required val text: String
}

/** `type a = ... and b = ...`: one recursive group of type definitions. */
@Element("types", function = "types")
@Children(TypeDef::class)
interface TypeGroup

/**
 * One type definition. Children: [FieldDecl]s for a record, or [VariantDecl]s for a variant type,
 * which with [polymorphic] is a closed polymorphic variant: `[ `A | `B of int ]`.
 */
@Element("type", function = "typeDef")
@Children(FieldDecl::class, VariantDecl::class)
interface TypeDef {
    @Required val name: String
    val params: List<String>
    val polymorphic: Boolean

    /** `= private { ... }`: readable outside its module, but only built inside it. */
    val isPrivate: Boolean
}

/** `name : type` in a record or inline record. */
@Element("field-decl", function = "fieldDecl")
interface FieldDecl {
    @Required val name: String
    @Required val type: String
}

/** A constructor. With [FieldDecl] children it carries an inline record, or, in a polymorphic variant, a tuple of their types. */
@Element("variant", function = "variant")
@Children(FieldDecl::class)
interface VariantDecl {
    @Required val name: String
}

/** `let [rec] b1 and b2 ...` at the top level. */
@Element("let", function = "let")
@Children(Binding::class)
interface LetGroup {
    @Required val rec: Boolean
}

/** `name params [: returnType] = body`. One child: the body. */
@Element("binding", function = "binding")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Binding {
    @Required val name: String
    /** Rendered parameters, e.g. `(x : int)` or `()`. */
    val params: List<String>
    val returnType: String
}

// ---- Expressions ----

@Element("ident", function = "ident")
interface Ident {
    @Required val name: String
}

/** A literal already in OCaml syntax: `3`, `0.1`, `"text"`, `true`, `()`. */
@Element("literal", function = "literal")
interface Literal {
    @Required val text: String
}

/** Function application. Children: the function, then each argument. */
@Element("apply", function = "apply")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Apply

/** A binary operator. Children: left, right. */
@Element("infix", function = "infix")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Infix {
    @Required val op: String
}

/** `fun params -> body`. One child: the body. */
@Element("lambda", function = "lambda")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Lambda {
    @Required val params: List<String>
}

/** `let [rec] name params = value in body`. Children: value, body. */
@Element("let-in", function = "letIn")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface LetIn {
    @Required val name: String
    val params: List<String>
    val rec: Boolean
}

/** `a; b; c`. */
@Element("sequence", function = "sequence")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Sequence

/** Children: condition, then, else. */
@Element("if", function = "ifThenElse")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface IfThenElse

/** Children: the scrutinee, then one [Case] per arm. */
@Element("match", function = "match")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class, Case::class,
)
interface Match

/** `| pattern -> body`. One child: the body. */
@Element("case", function = "case")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Case {
    @Required val pattern: String
}

/** `record.field`. One child: the record. */
@Element("field", function = "fieldGet")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface FieldGet {
    @Required val name: String
}

/** `{ a = x; b = y }`. Children: one [RecordEntry] per field. */
@Element("record", function = "recordLit")
@Children(RecordEntry::class)
interface RecordLit

/** `name = value` inside a record. One child: the value. */
@Element("entry", function = "entry")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface RecordEntry {
    @Required val name: String
}

/** A constructor, applied to its single child if it has one. */
@Element("construct", function = "construct")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Construct {
    @Required val name: String
}

/** `(expr : type)`, or with [coerce], `(expr :> type)`. One child: the expression. */
@Element("annotated", function = "annotated")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Annotated {
    @Required val type: String
    val coerce: Boolean
}

/** `[a; b; c]`. */
@Element("list", function = "listLit")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface ListLit

/** `(a, b)`. */
@Element("tuple", function = "tuple")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class,
)
interface Tuple

/** `{ base with a = x }`. Children: the base record, then one [RecordEntry] per replaced field. */
@Element("record-update", function = "recordUpdate")
@Children(
    Ident::class, Literal::class, Apply::class, Infix::class, Lambda::class, LetIn::class, Sequence::class,
    IfThenElse::class, Match::class, FieldGet::class, RecordLit::class, Construct::class, Annotated::class,
    ListLit::class, Tuple::class, RecordUpdate::class, RecordEntry::class,
)
interface RecordUpdate
