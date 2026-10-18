# The OCaml manual's tour, in fckt

These files translate [chapter 1 of the OCaml manual](https://ocaml.org/manual/5.5/coreexamples.html),
"The core language", into fckt. Each file is one section and prints what the OCaml toplevel shows
for its phrases. The `.out` file next to each is its expected output, which `./gradlew test`
checks.

```sh
fckt 04-records-and-variants.fk && ocaml 04-records-and-variants.ml
```

Values print the way Kotlin's `toString()` does. For example, the record `{num = 11; denom = 15}`
prints as `Ratio(num=11, denom=15)`, and the float `1.` prints as `1.0`.

## Section by section

| Section | Translation |
| --- | --- |
| [1 Basics](01-basics.fk) | Direct. `square` needs `x: Float`, because fckt's `*` works on both numeric types. OCaml rejects `1.0 * 2`, but fckt promotes the `Int`, as Kotlin does. |
| [2 Data types](02-data-types.fk) | `x :: l` is `[x, *l]`, and the patterns `[]` and `head :: tail` are `[]` and `[head, *tail]`. `'\n'.code` is `int_of_char`. Kotlin's `"""raw strings"""` stand in for `{|quoted strings|}`; fckt has no custom delimiters. |
| [3 Functions as values](03-functions.fk) | Direct. `sin'` is `sinPrime`. `fun x -> ...` returned from a function is `(x: Float) -> ...`. |
| [4 Records and variants](04-records-and-variants.fk) | `{num; denom}` patterns become `Ratio(num, denom)` in a `when`, or destructuring: `(num, denom) = r`. `{ ratio with num = ... }` is `ratio.copy(num = ...)`. `Int of int` is `Int(Int)`. Matching on `(n1, n2)` is `when (n1, n2)`. An `Int` plus a `Float` gives a `Float`, so `float i1 +. f2` is `i1 + f2`. |
| [4.1 Disambiguation](04.1-disambiguation.fk) | fckt knows the type of every record and variant, so it never needs a type annotation or guesses from the last defined type. `look_at_x_then_y` and `is_a_or_b`, both errors in OCaml, work. |
| [5 Imperative features](05-imperative.fk) | fckt has no arrays, loops, mutable fields or references, so each example is the functional version. `add_vect` works on lists. `translate` returns a moved copy. `random` takes its state and returns the next one. `insertion_sort` is section 2's `sort`. The `ref` type and an `idref` with a polymorphic mutable field have no equivalent. |
| [6 Exceptions](06-exceptions.fk) | fckt has no exceptions. `head` returns an `Option`, and `List.assoc` becomes `lookup`, which returns `None` where OCaml raises `Not_found`. `fixpoint`'s loop and local exception become recursion. `temporarily_set_reference` needs references, so it's left out. |
| [7 Lazy expressions](07-lazy.fk) | `lazy (e)` is `lazy(~e)`, and `Lazy.force x` is `x.value`, as in Kotlin. fckt has no lazy patterns, so `maybe_eval` reads the guard with `.value`. |
| [8 Symbolic processing](08-symbolic.fk) | Direct, with the `Unbound_variable` exception replaced by `error(...)`. |
| [9 Pretty-printing](08-symbolic.fk) | In the same file as section 8, since it uses its types. The printer returns a string rather than printing as it goes. |
| [10 Printf formats](10-printf.fk) | String templates replace format strings, and are type-checked too. `%a` printers become functions that return strings: `Some(3) \|> ppOption(ppInt)`. A stored format is a function of its arguments. |
| [11 Standalone programs](11-gcd.fk) | `main(args: List<String>)` receives the command-line arguments, and a list pattern picks them apart. |

## What the translation added to fckt

- Tuples: `(a, b)`, the type `(A, B)`, the pattern `(p, q)`, and `when (a, b)` to match on several values.
- List patterns `[]`, `[a, b]` and `[head, *tail]`, and spread in list literals: `[x, *xs]` and `[*xs, *ys]`. `*` is Kotlin's spread operator.
- `+` on lists.
- Destructuring with `(a, b) = value`, for tuples and records (by field order, like Kotlin's data classes).
- `record.copy(field = value)`.
- Characters `'a'`, with `.code`.
- Raw strings `"""..."""`.
- `lazy(~e)` and `Lazy<T>` with `.value`.
- `lookup` on lists of pairs.
- The math functions `sin`, `cos`, `atan`, `sqrt` and others.
- `main(args: List<String>)`.

## Not translated

These are deliberate omissions, not missing features, as long as fckt stays without mutation and exceptions:

- Arrays and in-place updates.
- `for` and `while` loops.
- Mutable record fields and references.
- Exceptions: `raise`, `try ... with`, and `exception` patterns in `match`.
- Polymorphic record fields (`'a. 'a -> 'a`).
- Lazy patterns.
- Custom-delimiter quoted strings.
