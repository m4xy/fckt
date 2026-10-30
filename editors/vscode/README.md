# fckt for VS Code and IntelliJ

Syntax highlighting for fckt (`.fk`) files. It's a TextMate grammar, so one file works in VS Code
and in any IntelliJ IDE. In VS Code, the extension also runs `fckt --language-server`, fckt's
language server, which says what's wrong with each file of the project as you type, and formats a
file as `fckt --format` does. It runs the `fckt` on `PATH`; the setting `fckt.path` names another.

## VS Code

```sh
npm install
npm run package                               # builds fckt-0.1.0.vsix
code --install-extension fckt-0.1.0.vsix
```

For quick edits to the grammar, you can instead symlink this folder into
`~/.vscode/extensions/fckt` and run **Developer: Reload Window** after each change.

## IntelliJ IDEA

Use the plugin in `editors/intellij`, which colours names by what they are. This grammar also works
as a TextMate bundle (Settings → Editor → TextMate Bundles → **+**, then pick this folder), but
TextMate's colours in IntelliJ can't tell a type parameter or a type with cases from any other
name.

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
