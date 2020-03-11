package sample.blog.eg

import akka.actor.Scheduler
import akka.pattern.after

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

object TimedPromise {

  final case class PromiseExpired(timeout: FiniteDuration)
    extends Exception(s"Promise not completed within $timeout!") with NoStackTrace

  def apply[A](timeout: FiniteDuration)(implicit
    ec: ExecutionContext,
    scheduler: Scheduler): Promise[A] = {
    val promise = Promise[A]()
    val expired = after(timeout, scheduler)(Future.failed(PromiseExpired(timeout)))
    promise.completeWith(expired)
    promise
  }
}
