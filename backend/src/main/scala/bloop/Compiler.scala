package bloop

import xsbti.compile._
import xsbti.T2
import java.util.Optional
import java.io.File

import bloop.internal.Ecosystem
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger
import sbt.internal.inc.{FreshCompilerCache, Locate, LoggedReporter, ZincUtil}

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sourceDirectories: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    classesDir: AbsolutePath,
    baseDirectory: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    previousResult: PreviousResult,
    logger: Logger
)

object Compiler {
  private final class ZincClasspathEntryLookup(previousResult: PreviousResult)
      extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      previousResult.analysis
    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  def compile(compileInputs: CompileInputs): CompileResult = {
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getCompilationOptions(inputs: CompileInputs): CompileOptions = {
      val sources = inputs.sourceDirectories.distinct
        .flatMap(src => Paths.getAll(src, "glob:**.{scala,java}"))
        .distinct
      val classesDir = inputs.classesDir.toFile
      val classpath =
        inputs.classpath.map(_.toFile) ++ inputs.scalaInstance.allJars ++ Array(classesDir)

      CompileOptions
        .create()
        .withClassesDirectory(classesDir)
        .withSources(sources.map(_.toFile))
        .withClasspath(classpath)
        .withScalacOptions(inputs.scalacOptions)
        .withJavacOptions(inputs.javacOptions)
    }

    def getSetup(compileInputs: CompileInputs): Setup = {
      val skip = false
      val empty = Array.empty[T2[String, String]]
      val lookup = new ZincClasspathEntryLookup(compileInputs.previousResult)
      val reporter = new LoggedReporter(100, compileInputs.logger)
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions =
        if (!compileInputs.scalaInstance.isDotty) IncOptions.create()
        else Ecosystem.supportDotty(IncOptions.create())
      val progress = Optional.empty[CompileProgress]
      Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
    }

    val scalaInstance = compileInputs.scalaInstance
    val compilers = compileInputs.compilerCache.get(scalaInstance)
    val inputs = getInputs(compilers)
    val incrementalCompiler = ZincUtil.defaultIncrementalCompiler
    incrementalCompiler.compile(inputs, compileInputs.logger)
  }
}
