package sample.blog

import java.lang.{Long => JLong}

import akka.event.LoggingAdapter
import akka.persistence.cassandra._
import akka.stream._
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.stage._
import com.datastax.driver.core._

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/*
Links:
  http://akka.io/blog/2016/10/21/emit-and-friends
  http://akka.io/blog/2016/07/30/mastering-graph-stage-part-1
  http://doc.akka.io/docs/akka/2.4/scala/stream/stream-customize.html?_ga=2.15138358.1258512146.1495883588-1678957595.1434051367#Using_asynchronous_side-channels
  http://doc.akka.io/docs/akka/2.4/scala/stream/stream-customize.html?_ga=2.73266707.1258512146.1495883588-1678957595.1434051367#Using_timers
  http://doc.akka.io/docs/akka/2.5.2/scala/stream/stream-customize.html

  http://akka.io/blog/2016/08/29/connecting-existing-apis

  https://github.com/mkubala/akka-stream-contrib/blob/feature/101-mkubala-interval-based-rate-limiter/contrib/src/main/scala/akka/stream/contrib/IntervalBasedRateLimiter.scala
*/

/*
http://doc.akka.io/docs/akka/2.5.2/scala/stream/stream-customize.html
A few simple guarantees.
  The callbacks are never called concurrently.
  The state encapsulated can be safely modified from the provided callbacks, without any further synchronization.
*/

/**
 * A Source that has one output and no inputs, it models a source of cassandra rows
 * associated with a persistenceId starting with offset.
 *
 * Taken from https://github.com/akka/alpakka/blob/master/cassandra/src/main/scala/akka/stream/alpakka/cassandra/CassandraSourceStage.scala
 * and adapted with respect to akka-cassandra-persistence schema
 */
final class PsJournal(client: Cluster, keySpace: String, journal: String, persistenceId: String, offset: Long, partitionSize: Long,
  log: LoggingAdapter, pageSize: Int) extends GraphStage[SourceShape[Row]] {
  val out: Outlet[Row] = Outlet[Row](akka.event.Logging.simpleName(this) + ".out")

  private val retryTimeout = 10000

  override val shape: SourceShape[Row] = SourceShape(out)

  private val queryByPersistenceId =
    s"""
       |SELECT persistence_id, partition_nr, sequence_nr, timestamp, timebucket, event FROM $journal WHERE
       |  persistence_id = ? AND
       |  partition_nr = ? AND
       |  sequence_nr >= ?
       """.stripMargin

  /*
    Selecting a separate dispatcher in Akka Streams is done by returning it from the initialAttributes of the GraphStage.
  */
  override protected def initialAttributes: Attributes =
    Attributes.name(persistenceId)
  //.and(ActorAttributes.dispatcher("cassandra-dispatcher"))

  private def navigatePartition(sequenceNr: Long, partitionSize: Long): Long = sequenceNr / partitionSize

  private def statement(preparedStmt: PreparedStatement, persistenceId: String,
    partition: JLong, sequenceNr: JLong, pageSize: Int) =
    new BoundStatement(preparedStmt).bind(persistenceId, partition, sequenceNr).setFetchSize(pageSize)

  @tailrec private def tryToConnect[T](n: Int)(f: => T): T =
    Try(f) match {
      case Success(x) => x
      case _ if n > 1 =>
        Thread.sleep(retryTimeout)
        log.info("Getting cassandra connection")
        tryToConnect(n - 1)(f)
      case Failure(e) => throw e
    }

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      /*
        It is not safe to access the state of any custom stage outside of the callbacks that it provides,
        just like it is unsafe to access the state of an actor from the outside.
        This means that Future callbacks should not close over internal state of custom stages because such access can be
        concurrent with the provided callbacks, leading to undefined behavior.

        All mutable state MUST be inside the GraphStageLogic
      */
      var requireMore = false
      var sequenceNr = offset
      var partitionIter = Option.empty[ResultSet]
      var onMessageCallback: AsyncCallback[Try[ResultSet]] = _
      val session = tryToConnect(Int.MaxValue)(client.connect(keySpace))
      val preparedStmt = session.prepare(queryByPersistenceId)

      override def preStart(): Unit = {
        implicit val _ = materializer.executionContext
        onMessageCallback = getAsyncCallback[Try[ResultSet]](onFinish)
        val partition = navigatePartition(sequenceNr, partitionSize): JLong
        val stmt = statement(preparedStmt, persistenceId, partition, sequenceNr, pageSize)
        session.executeAsync(stmt).asScala.onComplete(onMessageCallback.invoke)
      }

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            implicit val _ = materializer.executionContext
            partitionIter match {
              case Some(iter) if iter.getAvailableWithoutFetching > 0 ⇒
                sequenceNr += 1
                push(out, iter.one)
              case Some(iter) ⇒
                if (iter.isExhausted) {
                  //a current partition is exhausted, let's try to read from the next partition
                  val nextPartition = navigatePartition(sequenceNr, partitionSize): JLong
                  val stmt = statement(preparedStmt, persistenceId, nextPartition, sequenceNr, pageSize)
                  session.executeAsync(stmt).asScala.onComplete(onMessageCallback.invoke)
                } else {
                  //Your page size less than akka-cassandra-persistence partition size(cassandra-journal.target-partition-size)
                  //End of page but still have something to read in from the current partition,
                  log.info("Still have something to read in current partition seqNum: {}", sequenceNr)
                  iter.fetchMoreResults.asScala.onComplete(onMessageCallback.invoke)
                }
              case None ⇒
                //log.info("A request from a downstream had arrived before we read the first row")
                ()
            }
          }
        })

      /*
       * End of a page or the end
       */
      private def onFinish(rsOrFailure: Try[ResultSet]): Unit = {
        rsOrFailure match {
          case Success(iter) ⇒
            partitionIter = Some(iter)
            if (iter.getAvailableWithoutFetching > 0) {
              if (isAvailable(out)) {
                sequenceNr += 1
                push(out, iter.one)
              }
            } else {
              log.info("{} CompleteSource {} seqNum:{}", persistenceId, sequenceNr)
              completeStage()
            }

          case Failure(failure) ⇒ failStage(failure)
        }
      }

      override def postStop = {
        //cleaning up resources should be done here
        //session.closeAsync
      }
    }
}

object PsJournal {

  def apply[T: Reader : ClassTag](client: Cluster, keySpace: String, journal: String, persistenceId: String,
    offset: Long, log: LoggingAdapter, partitionSize: Long, pageSize: Int = 32) = {
    Source.fromGraph(new PsJournal(client, keySpace, journal, persistenceId, offset, partitionSize, log, pageSize))
      .map(_.as[T])
      .viaMat(new LastSeen)(Keep.right)
  }

  case class LastSeenException[T](th: Throwable, last: Option[T]) extends Exception(th)

  final class LastSeen[T] extends GraphStageWithMaterializedValue[FlowShape[T, T], Future[Option[T]]] {
    override val shape = FlowShape(Inlet[T]("in"), Outlet[T]("out"))

    override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Option[T]]) = {
      val matVal = Promise[Option[T]]
      val logic = new GraphStageLogic(shape) {
        import shape._

        private var current = Option.empty[T]

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            val element = grab(in)
            current = Some(element)
            push(out, element)
          }

          override def onUpstreamFinish(): Unit = {
            matVal.success(current)
            super.onUpstreamFinish()
          }

          override def onUpstreamFailure(ex: Throwable): Unit = {
            println("onUpstreamFailure")
            matVal.success(current)

            //don't fail here intentionally
            //super.onUpstreamFailure(LastSeenException(ex, current))
            super.onUpstreamFinish()
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = pull(in)
        })
      }
      (logic, matVal.future)
    }
  }
}