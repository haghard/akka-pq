package sample.blog.zio

import java.lang.{Long => JLong}
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, ThreadFactory}

import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import com.typesafe.scalalogging.StrictLogging
import scalaz.zio._

import scala.util.control.NonFatal

object ZIORuntime extends RTS with StrictLogging {

  case class ZioDaemons(name: String) extends ThreadFactory {
    private val namePrefix = s"$name-thread"
    private val threadNumber = new AtomicInteger(1)
    private val group: ThreadGroup = Thread.currentThread.getThreadGroup

    override def newThread(r: Runnable): Thread = {
      val t = new Thread(group, r, s"$namePrefix-${threadNumber.getAndIncrement}", 0L)
      t.setDaemon(true)
      t
    }
  }

  implicit class CqlStrings(val context: StringContext) extends AnyVal {
    def cql(args: Any*)(implicit session: Session): PreparedStatement = {
      val statement = new SimpleStatement(context.raw(args: _*))
      session.prepare(statement)
    }
  }

  override val threadPool = Executors.newFixedThreadPool(3, ZioDaemons("db-worker"))

  def readPartition(stmt: Statement, session: Session): IO[JournalReadError, ReaderState] =
    IO.async0 { (exitResult: ExitResult[JournalReadError, ReaderState] => Unit) =>
      threadPool.execute({ () =>
        val res =
          try {
            ExitResult.Completed(Connected(session.execute(stmt)))
          }
          catch {
            case NonFatal(ex) => ExitResult.Failed(JournalReadError(ex))
          }
        exitResult(res)
      })
      Async.later
    }

  def fetchMore(rs: ResultSet, pNum: JLong, isNewPage: Boolean = false): IO[JournalReadError, ReaderState] = {
    IO.async0 { (exitResult: ExitResult[JournalReadError, ReaderState] => Unit) =>
      //logger.debug("fetchMore {} {} {}", pNum, rs.isExhausted, isNewPage)
      if (rs.isExhausted) {
        if (isNewPage) Async.now(ExitResult.Completed(EndOfJournal))
        else Async.now(ExitResult.Completed(EndOfPartition(pNum)))
      } else {
        threadPool.execute({ () =>
          val res: ExitResult[JournalReadError, ReaderState] =
            try {
              val available = rs.getAvailableWithoutFetching
              //if (available != 32) throw new Exception("Boom !!!!!")
              logger.debug("fetch page size: {} {}", pNum, available)
              val page = Vector.fill(available)(rs.one)
              logger.debug("last seq_num: {}", page.last.getLong("sequence_nr"))
              ExitResult.Completed(NextRs(rs.fetchMoreResults.get))
            } catch {
              case NonFatal(ex) => ExitResult.Failed[JournalReadError, ReaderState](JournalReadError(ex))
            }
          exitResult(res)
        })
        Async.later
      }
    }
  }

  sealed trait ReaderState

  case class Connected(rs: ResultSet) extends ReaderState

  case class NextRs(rs: ResultSet) extends ReaderState

  case class EndOfPartition(pNum: Long) extends ReaderState

  case object EndOfJournal extends ReaderState

  case class JournalReadError(cause: Throwable) extends Exception(cause)

  def query(key: String, fetchSize: Int, pNum: JLong = 0l)(
    implicit session: Session): IO[JournalReadError, Unit] = {

    def createStmt(ps: PreparedStatement, pId: String, pNum: JLong) =
      ps.bind(pId, pNum).setFetchSize(fetchSize)

    def loop(r: ReaderState, ps: PreparedStatement, key: String,
      pNum: JLong, isNewPage: Boolean = false): IO[JournalReadError, Unit] = r match {
      case Connected(rs) =>
        fetchMore(rs, pNum, isNewPage).flatMap(loop(_, ps, key, pNum, isNewPage))
      case NextRs(rs) =>
        fetchMore(rs, pNum, false).flatMap(loop(_, ps, key, pNum, false))
      case EndOfPartition(pNum) =>
        val nextParNum = pNum + 1l
        readPartition(createStmt(ps, key, nextParNum), session).flatMap(loop(_, ps, key, nextParNum, true))
      case EndOfJournal =>
        IO.sync({
          logger.debug("*** EndOfJournal ***"); session.close()
        }) *> IO.unit
    }

    val ps = cql"SELECT persistence_id, sequence_nr FROM blogs.blogs_journal where persistence_id = ? and partition_nr = ?"
    readPartition(createStmt(ps, key, pNum), session)
      .flatMap(loop(_, ps, key, pNum, true))
  }


  implicit def guavaF2IO[T](guavaF: ListenableFuture[T]): IO[Throwable, T] = {
    //example
    /*for {
      p <- Promise.make[Throwable, T]
      s <- p.complete(null.asInstanceOf[T])
      r <- p.get
    } yield s*/

    IO.async0 { (cb: ExitResult[Throwable, T] => Unit) =>
      threadPool.execute({ () =>
        Futures.addCallback(guavaF, new FutureCallback[T] {
          override def onFailure(error: Throwable) =
            cb(ExitResult.Failed(error))

          override def onSuccess(result: T): Unit = {
            logger.debug("onSuccess")
            cb(ExitResult.Completed(result))
          }
        }, threadPool)
      })
      Async.later
    }
  }
}

//runMain sample.blog.zio.ZIOJournalReader
object ZIOJournalReader extends scalaz.zio.App {
  override def run(args: List[String]): IO[Nothing, ZIOJournalReader.ExitStatus] = {
    val cluster = new Cluster.Builder()
      .addContactPoints(InetAddress.getByName("192.168.77.97"))
      .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.LOCAL_ONE))
      .withPort(9042)
      .build()
    implicit val session = cluster.connect

    val q = ZIORuntime.query("7-Patrik", 1 << 5)
    val p = IO.supervise(q)
    p.attempt.map(_.fold(_ => -1, _ => 0)).map { exitCode =>
      println(s"ExitCode: $exitCode")
      cluster.close()
      ExitStatus.ExitNow(exitCode)
    }
  }
}