package sample.blog.identity

import java.net.InetAddress
import java.time.{ Instant, ZoneId, ZoneOffset, ZonedDateTime }
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ Executor, Executors, ThreadFactory, ThreadLocalRandom }

import akka.actor.{ ActorLogging, ActorRef, Kill, Props }
import akka.persistence.{ AtLeastOnceDelivery, PersistentActor, RecoveryCompleted }
import com.datastax.driver.core.utils.UUIDs
import com.datastax.driver.core.{ Cluster, ConsistencyLevel, PlainTextAuthProvider, QueryOptions }
import com.google.common.util.concurrent.{ FutureCallback, Futures, ListenableFuture }
import sample.blog.identity.MessageProcessor._

import scala.concurrent.{ ExecutionContext, Future, Promise }
import akka.pattern.pipe

import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success }

object MessageProcessor {

  abstract class CommonFailure(msg: String, cause: Throwable) extends Exception(msg, cause) with NoStackTrace
  case class TaskError(msg: String) extends CommonFailure(msg, null)

  sealed trait Event
  case class BeginTask(id: Long, sender: ActorRef) extends Event
  case class CommitTask(deliveryId: Long, faces: BeginTask) extends Event

  case class Confirm(id: Long)
  case class Watermark(deliveryId: Long, event: BeginTask)

  case class MLDaemons(name: String) extends ThreadFactory {
    private val namePrefix = s"$name-thread"
    private val threadNumber = new AtomicInteger(1)
    private val group: ThreadGroup = Thread.currentThread.getThreadGroup

    override def newThread(r: Runnable): Thread = {
      val t = new Thread(group, r, s"$namePrefix-${threadNumber.getAndIncrement}", 0L)
      t.setDaemon(true)
      t
    }
  }

  def asScalaFuture[T](lf: ListenableFuture[T], ex: Executor): Future[T] = {
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
    }, ex)
    promise.future
  }

  val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  private val ex = Executors.newFixedThreadPool(1, MLDaemons("ml"))
  implicit val ml = ExecutionContext.fromExecutor(ex)

  def props = Props(new MessageProcessor)
}

/*

 select * from identity.alpha_outcomes where service_id = 'alpha' and time_bucket = '2019-08-13' LIMIT 20;
 select * from identity.identity_journal where persistence_id = 'alpha' and partition_nr = 0;
 select token(service_id, time_bucket) from identity.alpha_outcomes;


 Kafka consumer uses cases:
  a) You can do processing quick enough and confirm messages in time.
  b) You can't do processing quick enough to confirm in time. Once we've read stuff from kafka we need to write it somewhere,
  so that if we crush we recover from the local journal and never forget messages we had received. (PersistentActor + AtLeastOnceDelivery)
  c) In a) and b) cases we relied on kafka managing message offsets. We also can manage them themselves.

 Kafka manages offsets. Microservice commits once the message is saved in its journal.
 Processing of messages happens from the microservice's journal.

 As soon as we have a message we persist it to the local journal,
 so that if we  crush can recover and never forget the messages we had received.

 The local journal act as a buffer.

*/
class MessageProcessor extends PersistentActor with AtLeastOnceDelivery with ActorLogging {
  val serviceName = "alpha"
  val outTable = "outcomes"

  val cfg = context.system.settings.config

  val ks = cfg.getString("cassandra-journal.keyspace")
  val host = cfg.getStringList("cassandra-journal.contact-points").get(0)

  override val persistenceId = serviceName

  val cluster = new Cluster.Builder()
    .addContactPoints(InetAddress.getByName(host)) //from config
    .withAuthProvider(new PlainTextAuthProvider(
      cfg.getString("cassandra-query-journal.authentication.username"),
      cfg.getString("cassandra-query-journal.authentication.password")
    ))
    .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.QUORUM))
    .withPort(9042)
    .build()

  //DateTimeFormatter.ofPattern("MMM dd yyyy hh:mm a")
  //DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm Z")

  val session = cluster.connect(ks)

  //data model that shows duplicates
  /*session.execute(
    s"""
      | CREATE TABLE IF NOT EXISTS $ks.${serviceName}_$outTable(
      | service_id text,
      | time_bucket text,
      | task_id bigint,
      | when timeuuid,
      | PRIMARY KEY ((service_id, time_bucket), task_id, when))
      | WITH CLUSTERING ORDER BY (task_id DESC, when DESC)
      |""".stripMargin
  ).one*/

  //data model that overrides duplicates because of
  session.execute(
    s"""
      | CREATE TABLE IF NOT EXISTS $ks.${serviceName}_$outTable(
      | service_id text,
      | time_bucket text,
      | task_id bigint,
      | when timeuuid,
      | PRIMARY KEY ((service_id, time_bucket), task_id))
      | WITH CLUSTERING ORDER BY (task_id DESC)
      |""".stripMargin
  ).one

  val ps = session.prepare(
    s"INSERT INTO ${serviceName}_$outTable(service_id, time_bucket, task_id, when) VALUES (?, ?, ?, now())")

  //override def preStart(): Unit = log.info("***** preStart ****")

  override def postStop(): Unit =
    cluster.close()

  override def receiveRecover: Receive = {
    var lastReqId = 0L

    {
      case req: BeginTask ⇒
        //during recovery, only messages with an un-confirmed deliveryId will be resend
        deliver(self.path)(Watermark(_, req))
      case CommitTask(deliveryId, ctx) ⇒
        lastReqId = ctx.id
        confirmDelivery(deliveryId)
      case RecoveryCompleted ⇒
        log.warning("RecoveryCompleted {}", lastReqId)
    }
  }

  def identifyAndProduce(deliveryId: Long, faces: BeginTask): Future[CommitTask] = {
    Future {
      //Assume a long running task
      log.info("start job: [id:{}-deliveryId:{}]", faces.id, deliveryId)
      val now = ZonedDateTime.now(ZoneOffset.UTC)
      val timeBucket = formatter.format(now)
      ps.bind(serviceName, timeBucket, faces.id: java.lang.Long)
    }.flatMap { bs ⇒ MessageProcessor.asScalaFuture(session.executeAsync(bs), ex) }
      .transform(_.map { rs ⇒
        if (ThreadLocalRandom.current.nextDouble > 0.95)
          throw TaskError(s"identifyAndProduce error while processing:${faces.id}")

        rs.one
        CommitTask(deliveryId, faces)
      })
  }

  override def receiveCommand: Receive = {
    case taskId: Long ⇒ //comes for kafka
      val replyTo = sender()
      persist(BeginTask(taskId, replyTo)) { request ⇒
        //This is how we guarantee that
        deliver(self.path)(deliveryId ⇒ Watermark(deliveryId, request))
        log.info("Message from kafka: {}", request.id)

        if (ThreadLocalRandom.current.nextDouble > 0.95)
          throw TaskError(s"ReceiveCommand error while processing:${request.id}")

        //As we have guarantied at least once locally, we can confirm to kafka.
        //Important: In should be done within the persist block
        request.sender ! Confirm(taskId)
      }

    case Watermark(deliveryId: Long, req: BeginTask) ⇒
      identifyAndProduce(deliveryId, req).onComplete {
        case Success(recognized) ⇒
          self ! recognized
        case Failure(ex) ⇒
          log.error(ex, "RecognizeFace error {}:{}", req.id, deliveryId)
          //triggers supervision strategy and uses priority queue for Kill message
          self ! Kill
      }

    case e: CommitTask ⇒
      persist(e) { ev ⇒
        log.info("commit job: [id:{}-deliveryId:{}]", e.faces.id, e.deliveryId)
        ev.faces.sender ! Confirm(ev.faces.id)

        if (ThreadLocalRandom.current.nextDouble > 0.95)
          throw TaskError(s"Persist commit error while processing:${ev.faces.id}")

        confirmDelivery(ev.deliveryId)
      }

    /*case akka.actor.Status.Failure(ex) ⇒
      log.error(ex, "Self kill !!!!")
      //context.stop(self)
      self ! Kill //trigger supervision strategy*/
  }
}