package uk.m4xy.fckt.intellij

import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.OutputToGeneralTestEventsConverter
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.SMCustomMessagesParsing
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.openapi.components.StoredProperty
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageVisitor
import jetbrains.buildServer.messages.serviceMessages.TestFailed
import jetbrains.buildServer.messages.serviceMessages.TestFinished
import jetbrains.buildServer.messages.serviceMessages.TestStarted
import jetbrains.buildServer.messages.serviceMessages.TestSuiteFinished
import jetbrains.buildServer.messages.serviceMessages.TestSuiteStarted
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel

/** What a ▶ in the gutter runs: a program's main, with kiln run, or tests, with kiln test. */
class FcktRunConfigurationType : ConfigurationType {
    override fun getDisplayName() = "fckt"
    override fun getConfigurationTypeDescription() = "Runs a fckt program or its tests with kiln"
    override fun getIcon() = FcktIcons.FILE
    override fun getId() = "FcktRunConfiguration"
    override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(FcktConfigurationFactory(this))
}

class FcktConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId() = "fckt"
    override fun createTemplateConfiguration(project: Project): RunConfiguration = FcktRunConfiguration(project, this, "fckt")
    override fun getOptionsClass() = FcktRunOptions::class.java
}

class FcktRunOptions : RunConfigurationOptions() {
    private val folderProperty: StoredProperty<String?> = string("").provideDelegate(this, "folder")
    private val taskProperty: StoredProperty<String?> = string("run").provideDelegate(this, "task")
    private val selectionProperty: StoredProperty<String?> = string("").provideDelegate(this, "selection")
    var folder: String get() = folderProperty.getValue(this) ?: ""; set(v) = folderProperty.setValue(this, v)
    var task: String get() = taskProperty.getValue(this) ?: "run"; set(v) = taskProperty.setValue(this, v)
    var selection: String get() = selectionProperty.getValue(this) ?: ""; set(v) = selectionProperty.setValue(this, v)
}

/** kiln run, or kiln test with what to run of them, in a project's folder. */
class FcktRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<FcktRunOptions>(project, factory, name) {
    public override fun getOptions(): FcktRunOptions = super.getOptions() as FcktRunOptions

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = object : SettingsEditor<FcktRunConfiguration>() {
        override fun resetEditorFrom(s: FcktRunConfiguration) {}
        override fun applyEditorTo(s: FcktRunConfiguration) {}
        override fun createEditor(): JComponent = JLabel("kiln ${options.task} in ${options.folder} ${options.selection}")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState = object : CommandLineState(environment) {
        override fun startProcess(): ProcessHandler {
            val args = listOf(options.task) + if (options.selection.isEmpty()) emptyList() else listOf("--", options.selection)
            val line = GeneralCommandLine(listOf(kilnExecutable()) + args).withWorkDirectory(options.folder)
                .withEnvironment("PATH", (System.getProperty("user.home") + "/.kiln/bin:") + (System.getenv("PATH") ?: ""))
            return KillableColoredProcessHandler(line).also { ProcessTerminatedListener.attach(it) }
        }

        override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
            if (options.task != "test") return super.execute(executor, runner)
            val handler = startProcess()
            val properties = FcktTestConsoleProperties(this@FcktRunConfiguration, executor)
            val console: ConsoleView = SMTestRunnerConnectionUtil.createAndAttachConsole("fckt", handler, properties)
            return DefaultExecutionResult(console, handler)
        }
    }
}

/** kiln's, as fckt's is found: KILN, ~/.kiln/bin, or the PATH's. */
internal fun kilnExecutable(): String {
    System.getenv("KILN")?.let { return it }
    val installed = File(System.getProperty("user.home") + "/.kiln/bin/kiln")
    return if (installed.canExecute()) installed.path else "kiln"
}

class FcktTestConsoleProperties(configuration: FcktRunConfiguration, executor: Executor) :
    SMTRunnerConsoleProperties(configuration, "fckt", executor), SMCustomMessagesParsing {
    override fun createTestEventsConverter(framework: String, properties: TestConsoleProperties): OutputToGeneralTestEventsConverter =
        KilnTestEvents(framework, properties)
}

/**
 * What kiln test prints, as the test runner's events: a file's name starts its suite, `passed name`
 * and `FAILED name: why` are its tests, and the count of them ends it.
 */
class KilnTestEvents(framework: String, properties: TestConsoleProperties) : OutputToGeneralTestEventsConverter(framework, properties) {
    private var suite: String? = null

    override fun processServiceMessages(text: String, outputType: Key<*>, visitor: ServiceMessageVisitor): Boolean {
        fun endSuite() = suite?.let { visitor.visitTestSuiteFinished(TestSuiteFinished(it)); suite = null }
        when (val event = kilnTestEvent(text)) {
            is KilnTestEvent.Suite -> {
                endSuite()
                suite = event.file
                visitor.visitTestSuiteStarted(TestSuiteStarted(event.file))
            }
            is KilnTestEvent.Passed -> {
                visitor.visitTestStarted(TestStarted(event.name, false, null))
                visitor.visitTestFinished(TestFinished(event.name, 0))
            }
            is KilnTestEvent.Failed -> {
                visitor.visitTestStarted(TestStarted(event.name, false, null))
                visitor.visitTestFailed(TestFailed(event.name, event.why))
                visitor.visitTestFinished(TestFinished(event.name, 0))
            }
            KilnTestEvent.Done -> endSuite()
            null -> return false
        }
        return true
    }
}

/** A line of what kiln test prints, as what it says of the tests. */
sealed class KilnTestEvent {
    data class Suite(val file: String) : KilnTestEvent()
    data class Passed(val name: String) : KilnTestEvent()
    data class Failed(val name: String, val why: String) : KilnTestEvent()
    data object Done : KilnTestEvent()
}

fun kilnTestEvent(text: String): KilnTestEvent? {
    val line = text.trimEnd('\n', '\r')
    val trimmed = line.trim()
    return when {
        line.startsWith("  ") && !line.startsWith("    ") && trimmed.endsWith(".fk") -> KilnTestEvent.Suite(trimmed)
        line.startsWith("    passed ") -> KilnTestEvent.Passed(trimmed.removePrefix("passed "))
        line.startsWith("    FAILED ") -> trimmed.removePrefix("FAILED ").let {
            KilnTestEvent.Failed(it.substringBefore(":"), it.substringAfter(": ", ""))
        }
        Regex("""\d+ passed, \d+ failed.*""").matches(trimmed) -> KilnTestEvent.Done
        else -> null
    }
}

/** Makes the configuration a ▶ in the gutter runs, from where it is. */
class FcktRunConfigurationProducer : LazyRunConfigurationProducer<FcktRunConfiguration>() {
    override fun getConfigurationFactory() = FcktConfigurationFactory(FcktRunConfigurationType())

    override fun setupConfigurationFromContext(
        configuration: FcktRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val target = runnable(context.psiLocation ?: return false) ?: return false
        configuration.options.folder = target.folder
        configuration.options.task = target.task
        configuration.options.selection = target.selection
        configuration.name = target.title
        return true
    }

    override fun isConfigurationFromContext(configuration: FcktRunConfiguration, context: ConfigurationContext): Boolean {
        val target = runnable(context.psiLocation ?: return false) ?: return false
        return configuration.options.folder == target.folder && configuration.options.task == target.task &&
            configuration.options.selection == target.selection
    }
}

/** A ▶ beside main and each test. */
class FcktRunLineMarkers : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null) return null
        val target = runnable(element) ?: return null
        if (target.at != element.textRange.startOffset) return null
        val icon = if (target.task == "test") AllIcons.RunConfigurations.TestState.Run else AllIcons.RunConfigurations.TestState.Run
        return Info(icon, ExecutorAction.getActions(0)) { "Run ${target.title}" }
    }
}

/** What runs from where: the folder, kiln's task, what of the tests, its title, and the name it's at. */
class Runnable(val folder: String, val task: String, val selection: String, val title: String, val at: Int)

/** What the name at [element] runs: main, a program; a test, itself. */
fun runnable(element: PsiElement): Runnable? {
    val file = element.containingFile as? FcktFile ?: return null
    val path = file.virtualFile?.path?.let(::File) ?: return null
    val project = generateSequence(path.parentFile) { it.parentFile }.firstOrNull { File(it, "build.fk").isFile } ?: return null
    val text = file.text
    val offset = element.textRange.startOffset
    val run = runnableAt(text, offset) ?: return null
    val (name, at, isTest) = run
    return if (isTest) Runnable(project.path, "test", "${path.name}::$name", name, at)
    else Runnable(project.path, "run", "", "${project.name} main", at)
}

/**
 * The function whose name is at [offset], if it runs: main, at a line's start, or one marked @Test,
 * on its line or the lines just before: its name, where it is, and whether it's a test.
 */
fun runnableAt(text: String, offset: Int): Triple<String, Int, Boolean>? {
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    val lineEnd = text.indexOf('\n', offset).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val name = Regex("""[A-Za-z_][A-Za-z0-9_]*""").find(text, offset)?.takeIf { it.range.first == offset }?.value ?: return null
    if (text.getOrNull(offset + name.length) != '(') return null
    // What's before the name on its line is only annotations.
    val before = line.substring(0, offset - lineStart)
    if (!Regex("""^(@[A-Za-z.]+(\([^)]*\))?\s+)*$""").matches(before)) return null
    if (name == "main" && before.isBlank()) return Triple(name, offset, false)
    // @Test on its line, or the lines just above it that are only annotations.
    val annotationsOnly = Regex("""^\s*(@[A-Za-z.]+(\([^)]*\))?\s*)+$""")
    val above = if (lineStart == 0) emptyList() else text.substring(0, lineStart - 1).split('\n').reversed().takeWhile { annotationsOnly.matches(it) }
    val marked = (listOf(before) + above).any { Regex("""@Test\b""").containsMatchIn(it) }
    return if (marked) Triple(name, offset, true) else null
}
