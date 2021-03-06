package sample.blog

import java.util.concurrent.Executor

import scala.concurrent.{ Future, Promise }
import scala.language.implicitConversions
import com.datastax.driver.core.{ PreparedStatement, Session, SimpleStatement }
import com.google.common.util.concurrent.{ FutureCallback, Futures, ListenableFuture }

import scala.util.Try
package object monix {

  import scala.concurrent.{ ExecutionContext, ExecutionContextExecutorService }
  import java.util.concurrent.{ AbstractExecutorService, TimeUnit }
  import java.util.Collections

  //https://gist.github.com/viktorklang/5245161
  object ExecutionContextExecutorServiceBridge {

    def apply(ec: ExecutionContext): ExecutionContextExecutorService = ec match {
      case null                                  ⇒ throw null
      case eces: ExecutionContextExecutorService ⇒ eces
      case other ⇒ new AbstractExecutorService with ExecutionContextExecutorService {
        override def prepare(): ExecutionContext = other
        override def isShutdown = false
        override def isTerminated = false
        override def shutdown() = ()
        override def shutdownNow() = Collections.emptyList[Runnable]
        override def execute(runnable: Runnable): Unit = other execute runnable
        override def reportFailure(t: Throwable): Unit = other reportFailure t
        override def awaitTermination(length: Long, unit: TimeUnit): Boolean = false
      }
    }
  }

  implicit def asScalaFuture[T](future: ListenableFuture[T])(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    future.addListener(() ⇒ promise.tryComplete(Try(future.get())), ec.asInstanceOf[Executor])

    /*
    future.addListener(() => {
      //BusySpinWaitStrategy: com.lmax.disruptor.util.ThreadHints.onSpinWait()
      while (! future.isDone) {
        java.lang.Thread.onSpinWait()
      }
      promise.tryComplete(Try(future.get()))
    }, ec.asInstanceOf[Executor])
    */
    /*com.google.common.util.concurrent.Futures.addCallback(lf, new FutureCallback[T] {
      def onFailure(error: Throwable): Unit = {
        promise.failure(error)
        ()
      }

      def onSuccess(result: T): Unit = {
        promise.success(result)
        ()
      }
    }, ExecutionContextExecutorServiceBridge(ec))*/

    promise.future
  }

  implicit class CqlStrings(val context: StringContext) extends AnyVal {
    def cql(args: Any*)(implicit session: Session, ec: ExecutionContext): Future[PreparedStatement] = {
      val statement = new SimpleStatement(context.raw(args: _*))
      session.prepareAsync(statement)
    }
  }
}