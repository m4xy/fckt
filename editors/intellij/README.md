# fckt for IntelliJ IDEA

A plugin that highlights fckt (`.fk`) files the way IntelliJ highlights Kotlin, and shows the
compiler's errors as you type. It uses the compiler itself: its parser says what each name is, and
its checker finds the errors.

## Building and installing

```sh
./gradlew :intellij:buildPlugin      # from the repository root: builds editors/intellij/build/distributions/fckt-0.1.0.zip
```

Then Settings → Plugins → ⚙ → **Install Plugin from Disk...**, and pick the zip. If you added the
TextMate bundle from `editors/vscode` before, remove it under Settings → Editor → TextMate Bundles.

The build compiles against the IntelliJ IDEA in `~/Applications/IntelliJ IDEA.app`; pass
`-PideaPath=/path/to/IntelliJ IDEA.app` for another. It needs IntelliJ IDEA 2026.1 or later.

## Colours

Each name is coloured by what it is, with the colour of its Kotlin counterpart, so a colour scheme
that suits Kotlin suits fckt:

| fckt | Kotlin colour |
| --- | --- |
| `spec Monad`, `type Validation { ... }`, `Option` | interface |
| `type Ratio(n: Int, d: Int)`, a case like `Some` | data class |
| `String`, `List` | class |
| `type Names = List<String>`, a spec's `type M<A>` | type alias |
| `module Options`, a functor's `M` | object |
| `A` in `List<A>`, inside what declares it | type parameter |
| a record's fields, `r.n`, and `x` in `Some(x) -> f(x)` | property |
| `spec`, `external`, `opaque` and `by`, where they're keywords | keyword |

Each can be changed on its own under Settings → Editor → Color Scheme → fckt.

## Go to declaration

Cmd-click a name, or use Go To → Declaration, to go where it's declared: a local or parameter
where it was bound, a function, type, case, field or module in this file, the directory's other
files, or the standard library (opened read-only). When the file checks, the checker says which
declaration a name means, so `xs.total()` goes to the `total` for `xs`'s type, and `M.pure` in a
functor goes to the spec's `pure`. When it doesn't, names are looked up as the compiler would:
inside out from where they're used, with `Ratios.make` looked up in `Ratios`.

## Errors

The file is compiled with its directory's other files, as `fckt` would, each time it changes. The
first error is underlined where it is; one in another file is shown at the top of the editor.

## Testing

```sh
./gradlew :intellij:test
```

checks the colours of some samples, where names in the examples go, and that every example scans
and colours without failing.
