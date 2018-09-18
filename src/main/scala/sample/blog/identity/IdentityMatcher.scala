package sample.blog.identity

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executor, Executors, ThreadFactory, ThreadLocalRandom}

import akka.cluster.sharding.ShardRegion
import akka.actor.{ActorLogging, ActorRef, Kill, Props}
import akka.persistence.{AtLeastOnceDelivery, PersistentActor}
import com.datastax.driver.core.utils.UUIDs
import com.datastax.driver.core.{Cluster, ConsistencyLevel, QueryOptions}
import com.google.common.util.concurrent.{FutureCallback, Futures, ListenableFuture}
import sample.blog.identity.IdentityMatcher.{Confirm, IdentifyFaces, MLDaemons, WaterMark}

import scala.concurrent.{ExecutionContext, Future, Promise}

object IdentityMatcher {

  case class IdentifyFaces(id: Long, sender: ActorRef)

  case class Confirm(id: Long, afterFailure: Boolean = false)

  case class WaterMark(deliveryId: Long, event: IdentifyFaces)

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
  val ex = Executors.newFixedThreadPool(2, MLDaemons("ml"))
  implicit val ml = ExecutionContext.fromExecutor(ex)

  override val persistenceId: String = "identity-matcher-actor"

  val cluster = new Cluster.Builder()
    .addContactPoints(InetAddress.getByName("192.168.77.85"))
    .withQueryOptions(new QueryOptions().setConsistencyLevel(ConsistencyLevel.LOCAL_ONE))
    .withPort(9042)
    .build()

  val session = cluster.connect("blogs")
  session.execute(
    "CREATE TABLE IF NOT EXISTS blogs.ident_results(id varchar, image_id bigint, when timeuuid, PRIMARY KEY (id, image_id)) WITH CLUSTERING ORDER BY (image_id DESC)")
    .one()

  val ps = session.prepare("INSERT INTO blogs.ident_results(id, image_id, when) VALUES (?, ?, now())")

  //UUIDs.timeBased()

  val r = ThreadLocalRandom.current.nextInt(8, 12)

  override def receiveRecover: Receive = handleIdentifyFace

  def identifyAndProduce(id: Long): Future[Unit] = {
    Future(ps.bind("faces", id: java.lang.Long))
      .flatMap(bs => IdentityMatcher.asScalaFuture(session.executeAsync(bs), ex))
      .map { rs =>
        rs.one
        log.info("!!!!!!!! Action: {} !!!!", id)
      }(ml)
  }

  //during recovery, only messages with an un-confirmed delivery is will be resent
  def handleIdentifyFace: Receive = {
    case WaterMark(deliveryId: Long, identifyFaces: IdentifyFaces) ⇒
      //log.info("recoveryRunning:{} deliveryId: {}", recoveryRunning, deliveryId)
      identifyAndProduce(identifyFaces.id)
        .onComplete {
          case scala.util.Success(_) ⇒
            log.info("confirmDelivery:{} deliveryId:{}", identifyFaces.id, deliveryId)
            //we had to confirm it again(potentially twice) as
            // we cant't distinguish if it's already been confirm or we recovered from a failure
            identifyFaces.sender ! Confirm(identifyFaces.id, true)
            confirmDelivery(deliveryId)
          case scala.util.Failure(_) ⇒
            self ! Kill
        }(ml)
  }

  override def receiveCommand: Receive = handleIdentifyFace orElse {
    case id: Long =>
      val replyTo = sender()
      persist(IdentifyFaces(id, replyTo)) { event =>
        log.info("persisted {}", event.id)

        //if we fail here we will get redelivery from the Bot
        //if (id % 2 == 0 && id > 5) throw new Exception("Boooom !!!")

        deliver(self.path)(deliveryId ⇒ WaterMark(deliveryId, event))

        //it we fail here, during recovery we will get a message with the deliveryId
        if (id % r == 0) throw new Exception("Crash !!!")

        //At this point it is safe to confirm
        sender() ! Confirm(id)
      }
  }
}