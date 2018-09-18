package sample.blog

import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import scala.concurrent.{Future, Promise}
import scala.language.implicitConversions
import com.datastax.driver.core.{PreparedStatement, Session, SimpleStatement}

package object monix {

  implicit def asScalaFuture[T](lf: ListenableFuture[T]): Future[T] = {
    val promise = Promise[T]()
    Futures.addCallback(lf, new FutureCallback[T] {
      def onFailure(error: Throwable): Unit = {
        promise.failure(error)
        ()
      }

      def onSuccess(result: T): Unit = {
        promise.success(result)
        ()
      }
    })
    promise.future
  }

  implicit class CqlStrings(val context: StringContext) extends AnyVal {
    def cql(args: Any*)(implicit session: Session): Future[PreparedStatement] = {
      val statement = new SimpleStatement(context.raw(args: _*))
      session.prepareAsync(statement)
    }
  }
}