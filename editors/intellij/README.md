# fckt for IntelliJ IDEA

A plugin that highlights fckt (`.fk`) files the way IntelliJ highlights Kotlin, shows the
compiler's errors as you type, and each name's inferred type on hover. It uses the compiler itself: its parser says what each name is, and
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

Names that are built in, like `Int`, `Option`, `Some` and `Ok`, go to `builtins.fk`, which
describes them; it isn't compiled.

## Find usages

Find Usages (Alt-F7), or Cmd-click on a declaration, lists the names that refer to it, worked out
the same way, so a local's are only its own and an overload's are only the calls that chose it.
Highlight Usages in File (Cmd-Shift-F7) works too.

## Errors

The file is compiled with its directory's other files, as `fckt` would, each time it changes. Each
error is underlined where it is; one in another file is shown at the top of the editor. The
compiler carries on past an error: past a declaration it can't parse, and past a function that
doesn't check, which what uses it then takes to be whatever it needs, so one mistake is reported
once.

## Types on hover

Hovering over a name, or Quick Documentation (F1, or Ctrl-J), shows what the checker worked out:
a function's signature with every type filled in and what it does (`does @Console`, `pure`, or
`does what f does` for one that does what its function parameter does); a local's, parameter's or
field's type; and for an annotation, whether it's an effect and which effects it passes on, so
`@Repository` says it passes on `@Persistence`.

## Testing

```sh
./gradlew :intellij:test
```

checks the colours of some samples, where names in the examples go, and that every example scans
and colours without failing; and, in a running IDE, that names resolve and Find Usages finds them.
