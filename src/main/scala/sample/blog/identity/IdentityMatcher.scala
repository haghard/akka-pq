package sample.blog.identity

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executor, Executors, ThreadFactory, ThreadLocalRandom}

import akka.cluster.sharding.ShardRegion
import akka.actor.{ActorLogging, ActorRef, Kill, Props}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor, RecoveryCompleted}
import com.datastax.driver.core.utils.UUIDs
import com.datastax.driver.core.{Cluster, ConsistencyLevel, QueryOptions}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import sample.blog.identity.IdentityMatcher._

import scala.concurrent.{ExecutionContext, Future, Promise}

object IdentityMatcher {

  case class IdentifyFaces(id: Long, sender: ActorRef)

  case class Confirm(id: Long, afterFailure: Boolean = false)

  case class RecognizeFace(deliveryId: Long, event: IdentifyFaces, reDelivered: Boolean = false)
  case class FacesRecognized(deliveryId: Long, faces: IdentifyFaces)

  val numberOfShards = 10

  /*
  val idExtractor: ShardRegion.ExtractEntityId = {
    case id: Long => (math.abs(id.hashCode).toString, id)
  }

  val shardResolver: ShardRegion.ExtractShardId = msg => msg match {
    case id: Long =>
      (math.abs(id.hashCode) % numberOfShards).toString
    case ShardRegion.StartEntity(id) ⇒
      (math.abs(id.hashCode) % numberOfShards).toString
  }
  */

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

  def props = Props(new IdentityMatcher)
}

class IdentityMatcher extends PersistentActor with AtLeastOnceDelivery with ActorLogging {
  val ex = Executors.newFixedThreadPool(1, MLDaemons("ml"))
  implicit val ml = ExecutionContext.fromExecutor(ex)

  override val persistenceId: String = "identity-matcher-actor"

  val cluster = new Cluster.Builder()
    .addContactPoints(InetAddress.getByName("192.168.77.83"))
    .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.LOCAL_ONE))
    .withPort(9042)
    .build()

  val session = cluster.connect("blogs")
  session.execute(
    "CREATE TABLE IF NOT EXISTS blogs.ident_results(id varchar, image_id bigint, when timeuuid, PRIMARY KEY (id, image_id)) WITH CLUSTERING ORDER BY (image_id DESC)")
    .one()

  val ps = session.prepare("INSERT INTO blogs.ident_results(id, image_id, when) VALUES (?, ?, now())")


  override def receiveRecover: Receive = {
    case event: IdentifyFaces =>
      //during recovery, only messages with an un-confirmed delivery will be resend
      deliver(self.path)(deliveryId ⇒ RecognizeFace(deliveryId, event, recoveryRunning))
    case FacesRecognized(deliveryId, _) =>
      confirmDelivery(deliveryId)
  }

  def identifyAndProduce(deliveryId: Long, faces: IdentifyFaces): Future[FacesRecognized] = {
    //Long running task
    //Future(Thread.sleep(420)).flatMap { _ =>
      Future(ps.bind("faces", faces.id: java.lang.Long))
        .flatMap(bs => IdentityMatcher.asScalaFuture(session.executeAsync(bs), ex))
        .map { rs =>
          rs.one
          FacesRecognized(deliveryId, faces)
        }
    //}
  }

  override def receiveCommand: Receive = {
    case id: Long =>
      val replyTo = sender()
      persist(IdentifyFaces(id, replyTo)) { event =>
        deliver(self.path)(deliveryId ⇒ RecognizeFace(deliveryId, event))
        log.info("Event from kafka: {}", event.id)

        if (ThreadLocalRandom.current.nextDouble > 0.98) {
          log.error("Error while processing: {}", event.id)
          throw new Exception("Crash !!!")
        }

        //reply to kafka
        event.sender ! Confirm(id)
      }

    case RecognizeFace(deliveryId: Long, faces: IdentifyFaces, rec) ⇒
      log.info("FacesRecognition job: [id:{}-deliveryId:{}] Redelivered:{}", faces.id, deliveryId, rec)
      import akka.pattern.pipe
      identifyAndProduce(deliveryId, faces).pipeTo(self)

    case e: FacesRecognized =>
      persist(e) { ev =>
        log.info("******************************** Recognized:{}", e.faces.id)
        //double confirmation
        ev.faces.sender ! Confirm(ev.faces.id, true)
        confirmDelivery(ev.deliveryId)
      }

    case akka.actor.Status.Failure(ex) =>
      log.error(ex, "Kiiiilllllllll !!!!")
      self ! Kill
  }
}