# fckt

fckt is a functional language with Kotlin's syntax, minus Kotlin's imperative assumptions. Source
files are `.fk`, and the compiler transpiles them to readable OCaml.

```kotlin
type Money(Int)                        // one unnamed field is called `value`
type Order(id: String, items: List<Money>)

interface OrderResult {                // always sealed: these are its only variants
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

See `examples/orders.fk` for a complete program.

## The language

| fckt | OCaml | Notes |
| --- | --- | --- |
| `type R(a: A, b: B)` | `type r = { a : a; b : b }` | `R(x, y)` or `R(a = x, b = y)` constructs it, and `r.a` reads a field. |
| `type R(A)` | `type r = { value : a }` | The same as `type R(value: A)`. |
| `interface S { V(x: X) W }` | `type s = V of { x : x } \| W` | A sealed sum type. |
| `type Box<T>(value: T)` | `type 't box = { value : 't }` | Type parameters work on types and functions. |
| `f(x: Int, y: Int): Int = e` | `let f (x : int) (y : int) : int = e` | Top-level order doesn't matter; mutual recursion becomes `let rec ... and`. |
| `name = e` | `let name = e` | A top-level value or a binding in a block. |
| `{ a = 1; f(a) }` | `let a = 1 in f a` | A block is an expression; it ends with its value. Unit expressions before that run in order (`e1; e2`). |
| `x -> e`, `(a, b) -> e` | `fun x -> e`, `fun a b -> e` | Lambdas never use braces, so `{` always starts a block. |
| `~e` | `fun () -> e`, `fun _ _ -> e` | A function that ignores its arguments. It takes as many as the expected type has, so `~true` can be a `(String, Money) -> Boolean`. With no expected function type it's a thunk, `() -> T`. |
| `x \|> f` | `x \|> f` | |
| `x \|> f(a)` | `x \|> f a` | When `f` takes one more argument than it's given, `x` is passed last. |
| `when (x) { P -> e ... }` | `match x with P -> e ...` | Patterns: literals, `_`, names, `V(p, q)`, `is V`, and `A, B` for either. |
| `is V -> x.field` | `V x__v -> x__v.field` | `is` narrows a variable, so the branch can read the variant's fields. |
| `when { c -> e; else -> e }` | `if c then e else ...` | |
| `if (c) a else b` | `if c then a else b` | `else` is required. |
| `x.f(a)` | `f x a` | Calls a top-level `f` on `x` when `x` has no member `f`. |
| `"$x and ${f(y)}"` | `"" ^ show x ^ ...` | Values print like Kotlin's `toString()`, e.g. `Money(value=3)`. |

`Int`, `Float` (or `Double`), `String`, `Boolean`, `Unit`, `Char`, `List<T>` and `Option<T>` are
built in. `[a, b]` is a list literal, and `Some(x)` and `None` build options. Arithmetic on an
`Int` and a `Float` gives a `Float`, as in Kotlin. Built-in methods are defined in
`types/Types.kt`: `map`, `filter`, `fold`, `sum`, `size`, `joinToString`, `getOrElse`, `toInt`,
and others.

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

`-o out.ml` picks the output file, and `-o -` prints the OCaml instead. The generated code needs
OCaml 5.1 or newer. `./gradlew test` compiles each test program and runs the result with `ocaml`
when it's installed.

Kastor comes from `../kastor` through a Gradle included build.

## Editor support

`editors/vscode` has syntax highlighting for VS Code and IntelliJ. See its README for how to install it.
