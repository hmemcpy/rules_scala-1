package annex

import java.io.ObjectOutputStream
import java.nio.file.Path
import sbt.testing.{Framework, Logger}
import scala.collection.mutable

class BasicTestRunner(framework: Framework, classLoader: ClassLoader, logger: Logger) extends TestFrameworkRunner {
  def execute(tests: Seq[TestDefinition], scopeAndTestName: String) =
    ClassLoader.withContextClassLoader(classLoader) {
      TestHelper.withRunner(framework, scopeAndTestName, classLoader) { runner =>
        val reporter = new TestReporter(logger)
        val tasks = runner.tasks(tests.map(TestHelper.taskDef(_, scopeAndTestName)).toArray)
        reporter.pre(framework, tasks)
        val taskExecutor = new TestTaskExecutor(logger)
        val failures = mutable.Set[String]()
        tasks.foreach { task =>
          reporter.preTask(task)
          taskExecutor.execute(task, failures)
          reporter.postTask()
        }
        reporter.post(failures.toSeq)
        !failures.nonEmpty
      }
    }
}

class ClassLoaderTestRunner(framework: Framework, classLoaderProvider: () => ClassLoader, logger: Logger)
    extends TestFrameworkRunner {
  def execute(tests: Seq[TestDefinition], scopeAndTestName: String) = {
    val reporter = new TestReporter(logger)

    val classLoader = framework.getClass.getClassLoader
    ClassLoader.withContextClassLoader(classLoader) {
      TestHelper.withRunner(framework, scopeAndTestName, classLoader) { runner =>
        val tasks = runner.tasks(tests.map(TestHelper.taskDef(_, scopeAndTestName)).toArray)
        reporter.pre(framework, tasks)
      }
    }

    val taskExecutor = new TestTaskExecutor(logger)
    val failures = mutable.Set[String]()
    tests.foreach { test =>
      val classLoader = classLoaderProvider()
      val isolatedFramework = new TestFrameworkLoader(classLoader, logger).load(framework.getClass.getName).get
      TestHelper.withRunner(isolatedFramework, scopeAndTestName, classLoader) { runner =>
        ClassLoader.withContextClassLoader(classLoader) {
          val tasks = runner.tasks(Array(TestHelper.taskDef(test, scopeAndTestName)))
          tasks.foreach { task =>
            reporter.preTask(task)
            taskExecutor.execute(task, failures)
            reporter.postTask()
          }
        }
      }
    }
    reporter.post(failures)
    !failures.nonEmpty
  }
}

class ProcessCommand(
  val executable: String,
  val arguments: Seq[String]
) extends Serializable

class ProcessTestRunner(
  framework: Framework,
  classpath: Seq[Path],
  command: ProcessCommand,
  logger: Logger with Serializable
) extends TestFrameworkRunner {
  def execute(tests: Seq[TestDefinition], scopeAndTestName: String) = {
    val reporter = new TestReporter(logger)

    val classLoader = framework.getClass.getClassLoader
    ClassLoader.withContextClassLoader(classLoader) {
      TestHelper.withRunner(framework, scopeAndTestName, classLoader) { runner =>
        val tasks = runner.tasks(tests.map(TestHelper.taskDef(_, scopeAndTestName)).toArray)
        reporter.pre(framework, tasks)
      }
    }

    val taskExecutor = new TestTaskExecutor(logger)
    val failures = mutable.Set[String]()
    tests.foreach { test =>
      val process = new ProcessBuilder((command.executable +: command.arguments): _*)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .start()
      try {
        val request = new TestRequest(
          framework.getClass.getName,
          test,
          scopeAndTestName,
          classpath.map(_.toString),
          logger
        )
        val out = new ObjectOutputStream(process.getOutputStream)
        try out.writeObject(request)
        finally out.close()
        if (process.waitFor() != 0) {
          failures += test.name
        }
      } finally process.destroy
    }
    reporter.post(failures.toSeq)
    !failures.nonEmpty
  }
}

trait TestFrameworkRunner {
  def execute(tests: Seq[TestDefinition], scopeAndTestName: String): Boolean
}
