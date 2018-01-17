package bloop.engine.tasks

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import scala.util.{Failure, Success, Try}

import monix.eval.{Task => MTask}
import monix.execution.{CancelableFuture, Scheduler}

/**
 * An asynchronous computation, possibly depending on other computations.
 */
class Task[+T](private val underlying: MTask[Try[T]],
               origTask: MTask[Try[T]],
               dependencies: Seq[Task[_]]) {

  /**
   * Registers that this task must be run after `dependencies` have finished.
   *
   * @param moreDependencies The tasks that this task depend on.
   * @return A new task that will first run `dependencies`, and then itself.
   */
  def dependsOn(moreDependencies: Task[_]*): Task[T] = {
    val allDependencies = dependencies ++ moreDependencies
    val inTasks = allDependencies.map(_.underlying)
    val task = MTask.gatherUnordered(inTasks).flatMap { _ =>
      origTask
    }
    new Task(task, origTask, allDependencies)
  }

  /** Run this task asynchronously */
  def runAsync(implicit s: Scheduler): CancelableFuture[Try[T]] = {
    underlying.runAsync
  }

  /**
   * A new task whose result is the result of this task transformed by `op`.
   *
   * @param op The operation to execute on the result of this task.
   * @return A new task whose result is the result of this task transformed by `op`.
   */
  def map[V](op: T => V): Task[V] = {
    val task = underlying.map(_.map(op))
    new Task(task, task, Nil)
  }

  /**
   * A new task whose result is the result of the task generated by `op`.
   *
   * @param op The operation to execute on the result of this task to create a new task.
   * @return A new task whose result is the result of the task generated by `op`.
   */
  def flatMap[V](op: T => Task[V]): Task[V] = {
    val task: MTask[Try[V]] = underlying.flatMap {
      case Success(result) => op(result).underlying
      case Failure(ex) => MTask(Failure(ex))
    }
    new Task(task, task, Nil)
  }

  /**
   * Awaits at most `duration` for this task to complete.
   *
   * @param duration The maximum duration to wait for this task to complete.
   * @return The result of this task.
   */
  def await(duration: Duration = Duration.Inf)(implicit s: Scheduler): Try[T] = {
    Await.result(runAsync, duration)
  }
}

object Task {

  /**
   * Creates a new task that will run `op`.
   *
   * @param op The computation to perform.
   * @return A task that will perform `op` when run.
   */
  def apply[T](op: => T): Task[T] = {
    val mTask = MTask(op).materialize.memoize
    new Task(mTask, mTask, Nil)
  }

  /**
   * Creates a task graph to execute at `base`.
   *
   * @param dependencies A function that, given a node, returns its dependencies.
   * @param mkTask       A function that, given a node, returns the task to execute.
   * @param base         The root of the task graph.
   * @return A task that depends on all the dependencies of `base`.
   */
  def makeTaskGraph[T, U](dependencies: T => Seq[T], mkTask: T => Task[U])(base: T): Task[U] = {
    val cache = mutable.Map.empty[T, Task[U]]
    def taskFor(node: T): Task[U] = {
      cache.getOrElseUpdate(node, {
        val depTasks = dependencies(node).map(taskFor)
        mkTask(node).dependsOn(depTasks: _*)
      })
    }
    taskFor(base)
  }
}
