/*
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc

/* Super hacky overriding of the MainGenericRunner used by partest */

import org.scalajs.ir

import org.scalajs.io._

import org.scalajs.logging._

import org.scalajs.linker._
import org.scalajs.linker.irio._

import org.scalajs.jsenv._
import org.scalajs.jsenv.nodejs.NodeJSEnv

import com.google.common.jimfs.Jimfs

import scala.tools.partest.scalajs.ScalaJSPartestOptions._

import java.io.File
import java.net.URL
import java.nio.file.Files
import scala.io.Source
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

import Properties.{ versionString, copyrightString }
import GenericRunnerCommand._

class MainGenericRunner {
  def errorFn(ex: Throwable): Boolean = {
    ex.printStackTrace()
    false
  }
  def errorFn(str: String): Boolean = {
    scala.Console.err println str
    false
  }

  val optMode = OptMode.fromId(System.getProperty("scalajs.partest.optMode"))

  def readSemantics() = {
    import org.scalajs.linker.CheckedBehavior.Compliant

    val opt = Option(System.getProperty("scalajs.partest.compliantSems"))
    val compliantSems =
      opt.fold[List[String]](Nil)(_.split(',').toList.filter(_.nonEmpty))

    compliantSems.foldLeft(Semantics.Defaults) { (prev, compliantSem) =>
      compliantSem match {
        case "asInstanceOfs"         => prev.withAsInstanceOfs(Compliant)
        case "arrayIndexOutOfBounds" => prev.withArrayIndexOutOfBounds(Compliant)
        case "moduleInit"            => prev.withModuleInit(Compliant)
        case "strictFloats"          => prev.withStrictFloats(true)
      }
    }
  }

  def process(args: Array[String]): Boolean = {
    val command = new GenericRunnerCommand(args.toList, (x: String) => errorFn(x))

    if (!command.ok) return errorFn("\n" + command.shortUsageMsg)
    else if (command.settings.version) return errorFn("Scala code runner %s -- %s".format(versionString, copyrightString))
    else if (command.shouldStopWithInfo) return errorFn("shouldStopWithInfo")

    if (command.howToRun != AsObject)
      return errorFn("Scala.js runner can only run an object")

    val logger = new ScalaConsoleLogger(Level.Warn)
    val semantics0 = readSemantics()
    val semantics = if (optMode == FullOpt) semantics0.optimized else semantics0

    val moduleInitializers = Seq(ModuleInitializer.mainMethodWithArgs(
        command.thingToRun, "main", command.arguments))

    val linkerConfig = StandardLinker.Config()
      .withCheckIR(true)
      .withSemantics(semantics)
      .withSourceMap(false)
      .withOptimizer(optMode != NoOpt)
      .withClosureCompiler(optMode == FullOpt)
      .withBatchMode(true)

    val linker = StandardLinker(linkerConfig)

    val sjsCode = {
      val file = Jimfs.newFileSystem().getPath("partest.js")
      val out = new WritableFileVirtualBinaryFile(file)

      val cache = (new IRFileCache).newCache
      val result = FileScalaJSIRContainer
        .fromClasspath(command.settings.classpathURLs.map(urlToFile _))
        .flatMap(cache.cached _)
        .flatMap(linker.link(_, moduleInitializers, LinkerOutput(out), logger))

      Await.result(result, Duration.Inf)

      file
    }

    val input = Input.ScriptsToLoad(sjsCode :: Nil)
    val config = RunConfig().withLogger(logger)

    val run = new NodeJSEnv().start(input, config)
    try {
      Await.result(run.future, Duration.Inf)
    } finally {
      run.close()
    }

    true
  }

  private def urlToFile(url: java.net.URL) = {
    try {
      new File(url.toURI())
    } catch {
      case e: java.net.URISyntaxException => new File(url.getPath())
    }
  }
}

object MainGenericRunner extends MainGenericRunner {
  def main(args: Array[String]): Unit = {
    if (!process(args))
      System.exit(1)
  }
}
