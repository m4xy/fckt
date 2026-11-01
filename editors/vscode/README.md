# fckt for VS Code and IntelliJ

Syntax highlighting for fckt (`.fk`) files. It's a TextMate grammar, so one file works in VS Code
and in any IntelliJ IDE. In VS Code, the extension also runs `fckt --language-server`, fckt's
language server: errors as you type, types and effects on hover, go to definition, find references,
rename, hints for inferred types and effects, quick fixes for missing imports and effects, and
formatting, as the main README describes. It runs the `fckt` on `PATH`; the setting `fckt.path` names another.

## VS Code

```sh
npm install
npm run package                               # builds fckt-0.1.0.vsix
code --install-extension fckt-0.1.0.vsix
```

For quick edits to the grammar, you can instead symlink this folder into
`~/.vscode/extensions/fckt` and run **Developer: Reload Window** after each change.

## IntelliJ IDEA

Use the plugin in `editors/intellij`, which runs the same language server through IntelliJ's own
client, for everything above and for colouring each name by what it is; and adds a ▶ beside
`main` and each test, which runs it with kiln, its tests' results in IntelliJ's test runner, and
icons beside each spec and module, and their values, that go to what implements them or what they
implement. Build it with `./gradlew :intellij:buildPlugin` and install
`editors/intellij/build/distributions/fckt-0.1.0.zip` from disk.

## Changing the grammar

The grammar is `syntaxes/fckt.tmLanguage.json`. `sample.fk` uses every construct, so you can check
your changes against it:

```sh
npm run tokens -- sample.fk
```

This prints each token with the scope it got, using the same tokenizer VS Code uses.

The scopes follow the usual TextMate names, so themes color them. Declared types are
`entity.name.type`, built-in types are `entity.name.type.builtin`, the cases of a `type Name { ... }` are
`entity.name.type.variant`, as are `Some` and `None`, spec names are `entity.name.type.interface` and `string.other.interface` (so IntelliJ colours them as Kotlin's interfaces), lambda and function parameters are `variable.parameter`, and `|>` and
`~` are `keyword.operator.pipe` and `keyword.operator.thunk`.

It's regex based, so it can't use types. For example, a variant in a `when` pattern is colored as a
type, and a named argument at the start of a line is colored like a binding.
