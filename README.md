# fckt

fckt is a functional language with Kotlin's syntax, minus Kotlin's imperative assumptions. Source
files are `.fk`, and the compiler transpiles them to readable OCaml.

```kotlin
type Money(Int)                        // one unnamed field is called `value`
type Order(id: String, items: List<Money>)

type OrderResult {                     // a type with cases: these are its only ones
  Success(orderId: String, total: Money)
  InvalidOrder(reason: String)
}

calculateTotal(items: List<Money>, code: Option<String>) = {
  subtotal = items.map(m -> m.value).sum()   // no `val`: every binding is immutable
  discount = when (code) {
    Some("X") -> (subtotal * 0.1).toInt()
    else -> 0
  }
  Money(max(0, subtotal - discount))
}
```

See `examples/orders.fk` for a complete program, and `examples/manual` for the OCaml manual's
tour of the core language, translated section by section.

## The language

| fckt | OCaml | Notes |
| --- | --- | --- |
| `type R(a: A, b: B)` | `type r = { a : a; b : b }` | `R(x, y)` or `R(a = x, b = y)` constructs it, and `r.a` reads a field. |
| `type R(A)` | `type r = { value : a }` | The same as `type R(value: A)`. |
| `type S { V(x: X), W }` | `type s = V of { x : x } \| W` | A type with cases, which a value is exactly one of (a sealed sum type). Parentheses make a record, braces make cases. |
| `type Vowel { Alphabet.A, Alphabet.E }` | `type vowel = [ `Alphabet_A \| `Alphabet_E ]` | A type can take in other types' cases, one at a time (`Alphabet.A`) or all of them (`*Vowel`). A `Vowel` can then be used wherever an `Alphabet` is expected, and `is Vowel` narrows an `Alphabet` to a `Vowel`. When branches give different types, the result is the smallest declared type with all their cases. Types that share cases become polymorphic variants; the rest stay ordinary ones. See `examples/shared-cases.fk`. |
| `module Ratios { ... }` | `module Ratios = struct ... end` | Groups declarations. Inside, members use each other directly; outside, they're named through the module: `Ratios.make(1, 2)`, `Shapes.Circle(1.0)`, `Shapes.Shape`. Modules can nest. See `examples/modules.fk`. |
| `ratios.fk` | `module Ratios = struct ... end` | Each file is a module named after it (`shared-cases.fk` is `SharedCases`), with no imports: a name a file uses but doesn't declare brings in the file of that name, from the program's directory or a directory given with `fckt -I dir`. The program is compiled to one `.ml`. |
| `List<A>.second(): Option<A> = ...` | `let list_second (this : 'a list) : 'a option = ...` | An extension function, called as `xs.second()`. It's chosen by the receiver's type, which must be known, so `List<Int>.sum()` and `List<Float>.sum()` can both exist; the most specific one that fits wins. Inside, the receiver is `this`. Bare names in the receiver, like `A`, are its type parameters. Without parentheses, `String.size: Int = ...`, it's a property: `s.size`. See `examples/extensions.fk`. |
| `external List<A>.map<B>(f: (A) -> B): List<B> = "List.map f this"` | `List.map f xs` at each call | A function whose body is OCaml: a function applied to the parameters, by name, with `~name` for a labelled argument. It's written out at each use, so it has no OCaml definition of its own, and the compiler trusts its declared types, as OCaml trusts its own `external`. |
| `private gcd(...)` | left out of the module's `sig` | Visible only inside its module. A public member can't expose a private type. |
| `type Ratio private(n: Int, d: Int)` | `type ratio = private { ... }` in the `sig` | Like Kotlin's `private constructor`: readable and matchable anywhere, but only its module can build or `copy` one. |
| `spec Counter { type T; inc(c: T): T }` | `module type Counter = sig type t val inc : t -> t ... end` | What a module must define. Each of its types gets a show function, so values of it work in string templates. Members are one per line. |
| `module Tally : Counter { type T = Int ... }` | `module Tally : Counter = struct ... end` | Outside, only what the spec lists is visible, and its types are abstract: a `Tally.T` can be passed around but not used as an `Int`, built or looked into. Members must have the spec's types; generic ones must work for any type. See `examples/specs.fk`. |
| `module Options : Monad<Option> { ... }` | `module Options : Monad with type 'a m = 'a option = struct ... end` | A spec's type arguments say what its types are, in order, so they aren't abstract outside. A type that takes parameters takes a type that takes as many more, at the end or where `_` marks them: `Monad<Option>` makes `M<A>` an `Option<A>`, and `Monad<Result<_, String>>` a `Result<A, String>`. They work on a functor's parameters too: `module F<M : Ordered<Int>>`. See `examples/monads.fk`. |
| `module SortedSet<M : Ordered> { ... }` | `module SortedSet (M : Ordered) = struct ... end` | A functor: a module that takes modules. Inside, `M` has only what `Ordered` lists, with its types abstract, so the body works for any module that follows it. Members are used through a module made from it. See `examples/functors.fk`. |
| `module IntSet = SortedSet<IntOrder>` | `module IntSet = SortedSet (IntOrder)` | The module a functor makes. The argument needs everything the spec lists, with the right types. If it follows the spec as `module IntOrder : Ordered`, its types stay abstract, as in OCaml, so `IntSet` takes only `IntOrder.T`s; as `Ordered<Int>`, or without a spec, `IntSet` takes `Int`s. |
| `Options { x by half(n); x + 1 }` | `let ( let* ) = Options.bind in let* x = half n in Options.pure (x + 1)` | A module's name before a block runs the block in it, through the module's `bind` and `pure` (as `Monad` has). `x by m` takes the value out of `m`, or stops the block where `bind` would. The last line goes back in with `pure`. `by` takes a name, `_` or a tuple pattern, and goes only directly in the block, not in a lambda inside it. It works with a functor's parameter too: `M { ... }`. See `examples/monads.fk`. |
| `Validated { n by checkName(s); a by checkAge(i); User(n, a) }` | `let+ n = check_name s and+ a = check_age i in User (n, a)` | A module with `map` and `product` but no `bind` (as `Applicative` has) runs its `by` lines independently. Every line runs, and `product` decides what to keep, so a validation can report every error rather than the first. The lines can't use each other's values and go together: other lines go before them or after them, and lines after can use them. See `examples/independent-steps.fk`. |
| `type Names = List<String>` | `type names = string list` | Another name for a type, with type parameters if needed: `type Pair<A> = (A, A)`. |
| `type Box<T>(value: T)` | `type 't box = { value : 't }` | Type parameters work on types and functions. |
| `f(x: Int, y: Int): Int = e` | `let f (x : int) (y : int) : int = e` | Top-level order doesn't matter; mutual recursion becomes `let rec ... and`. |
| `name = e` | `let name = e` | A top-level value or a binding in a block. |
| `{ a = 1; f(a) }` | `let a = 1 in f a` | A block is an expression; it ends with its value. Unit expressions before that run in order (`e1; e2`). |
| `x -> e`, `(a, b) -> e` | `fun x -> e`, `fun a b -> e` | Lambdas never use braces, so `{` always starts a block. |
| `~e` | `fun () -> e`, `fun _ _ -> e` | A function that ignores its arguments. It takes as many as the expected type has, so `~true` can be a `(String, Money) -> Boolean`. With no expected function type it's a thunk, `() -> T`. |
| `x \|> f` | `x \|> f` | |
| `x \|> f(a)` | `x \|> f a` | When `f` takes one more argument than it's given, `x` is passed last. |
| `when (x) { P -> e ... }` | `match x with P -> e ...` | Patterns: literals, `_`, names, `V(p, q)`, `is V`, `(p, q)`, `[]`, `[h, *t]`, and `A, B` for either. |
| `when (a, b) { ... }` | `match (a, b) with ...` | Matches on several values at once. |
| `(a, b)`, `(A, B)` | `(a, b)`, `a * b` | A tuple, and its type. |
| `(x, y) = e` | `let (x, y) = e in` | Destructures a tuple, or a record's fields in declaration order. |
| `[x, *xs]`, `xs + ys` | `x :: xs`, `xs @ ys` | `*` spreads a list into a list literal, like Kotlin's spread operator. |
| `r.copy(a = x)` | `{ r with a = x }` | |
| `'a'`, `"""raw"""` | `'a'`, a string | Raw strings have no escapes, but still interpolate. |
| `lazy(~e)`, `l.value` | `Lazy.from_fun`, `Lazy.force` | |
| `is V -> x.field` | `V x__v -> x__v.field` | `is` narrows a variable, so the branch can read the variant's fields. |
| `when { c -> e; else -> e }` | `if c then e else ...` | |
| `if (c) a else b` | `if c then a else b` | `else` is required. |
| `x.f(a)` | `f x a` | Calls a top-level `f` on `x` when `x` has no member `f`. |
| `main(args: List<String>)` | `let () = main ...` | `main` may take the command-line arguments. |
| `"$x and ${f(y)}"` | `"" ^ show x ^ ...` | Values print like Kotlin's `toString()`, e.g. `Money(value=3)`. |

`Int`, `Float` (or `Double`), `String`, `Boolean`, `Unit`, `Char`, `List<T>`, `Option<T>`,
`Result<A, E>` and `Lazy<T>` are built in. `Result` is OCaml's `('a, 'e) result`, built with `Ok(x)` and
`Error(e)`, with the value first as in OCaml; `Monad<Result<_, String>>` fixes its error. `assert(condition)` and `assertEquals(expected, actual)` stop the program
when they fail, giving the line and, for `assertEquals`, both values. `[a, b]` is a list literal, `Some(x)` and `None` build options, and `Unit`, as in Kotlin, is also the one value of its type. Arithmetic on an
`Int` and a `Float` gives a `Float`, as in Kotlin. Methods such as `map`, `filter`, `fold`, `sum`,
`size`, `getOrElse` and `toInt`, and functions such as `max` and `sqrt`, are externals in
`std/prelude.fk`; only `toString`, `joinToString`, `assert` and `assertEquals` are built in.

## Standard library

`src/main/resources/std` is written in fckt, and comes into a program as it's used, like any
other file. Only what a program mentions goes into its OCaml, and a program's own declaration or
file of the same name takes its place.

| | |
| --- | --- |
| `prelude.fk` | Needs no module's name: `spec Monad`, `spec Applicative`, `type Validation<A, E> { Valid(value: A), Invalid(errors: List<E>) }`, and the methods and functions on built-in types, as externals. |
| `Options` | Option as a monad: `Options { x by half(n); x + 1 }`. |
| `Results` | Result as a monad, for any error type, with `mapError` and `toOption`. |
| `Validated` | Validation without bind, so `Validated { ... }` keeps every error; `fromResult` and `toResult`. |
| `Lists` | List as a monad, for every combination: `Lists { x by xs; y by ys; (x, y) }`, with `guard`. |

See `examples/stdlib.fk`.

## How it works

1. `syntax/`: the lexer and recursive-descent parser produce the fckt AST. Newlines end
   statements only inside braces.
2. `types/Checker.kt`: Hindley–Milner inference with annotations as hints. Top-level declarations
   are checked in dependency order, and each strongly connected group is generalized together.
   The checker records types plus the decisions the translation depends on: which `+` to use, what
   `x.f(...)` means, and when a field or constructor name is ambiguous in OCaml.
3. `ocaml/Grammar.kt`: the OCaml AST, written as [Kastor](../kastor) `@Element` interfaces.
   `ocaml/Lowering.kt` builds the output tree with the generated DSL.
4. `ocaml/Printer.kt`: prints the resolved tree as OCaml, adding the parentheses OCaml needs.

## Using it

```sh
./gradlew installDist
build/install/fckt/bin/fckt examples/orders.fk   # writes examples/orders.ml
ocaml examples/orders.ml
```

`-o out.ml` picks the output file, and `-o -` prints the OCaml instead. `-I dir` adds a directory to look in for the files a program uses. The generated code needs
OCaml 5.1 or newer. `./gradlew test` compiles each test program and runs the result with `ocaml`
when it's installed.

Kastor comes from `../kastor` through a Gradle included build.

## Editor support

`editors/vscode` has syntax highlighting for VS Code and IntelliJ. See its README for how to install it.
