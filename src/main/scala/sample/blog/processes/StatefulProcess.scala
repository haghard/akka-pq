package sample.blog.eg

import java.util.concurrent.ThreadLocalRandom

import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.QueueOfferResult.{ Dropped, Enqueued }
import akka.stream.scaladsl.{ Flow, FlowWithContext, Keep, Sink, Source, SourceQueueWithComplete }
import akka.stream.{ ActorAttributes, ActorMaterializer, Attributes, Materializer, OverflowStrategy, QueueOfferResult }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }

//runMain sample.blog.eg.StatefulProcess
////https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
object StatefulProcess {

  case class ProcessorUnavailable(name: String)
    extends Exception(s"Processor $name cannot accept requests at this time!")

  case class ProcessorError(result: QueueOfferResult)
    extends Exception(s"Unexpected queue offer result: $result!")

  sealed trait Cmd {
    def id: Long
    def ts: Long
  }

  final case class AddUser(id: Long, ts: Long = System.currentTimeMillis) extends Cmd

  final case class RmUser(id: Long, ts: Long = System.currentTimeMillis) extends Cmd

  sealed trait Evn

  sealed trait Reply

  final case class Added(seqNum: Long) extends Reply

  final case class Removed(seqNum: Long) extends Reply

  final case class UserState(users: Set[Long] = Set.empty, current: Long = -1L /*, p: Promise[Seq[Reply]] = null*/ )

  final case class UserState0(users: Set[Long] = Set.empty, seqNum: Long = 0L)

  /*val sourceWithContext: SourceWithContext[Msg, MsgContext, NotUsed] =
    SourceWithContext
    .fromTuples(
      Source(List(Msg("data-1", "meta-1"), Msg("data-2", "meta-2")))
        .map {
          case Msg(data, context) => (data, context) // a recipe for decomposition
         }
     )*/

  /*
  def aboveAverage: Flow[Double, Double, ] =
      Flow[Double].statefulMapConcat { () ⇒
        var sum = 0
        var n   = 0
        rating ⇒ {
          sum += rating
          n += 1
          val average = sum / n
          if (rating >= average) rating :: Nil
          else Nil
        }
      }
  */
  //https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
  def statefulBatchedFlow(
    userState: UserState0, bs: Int
  )(implicit ec: ExecutionContext): FlowWithContext[AddUser, Promise[Reply], Reply, Promise[Reply], Any] = {
    val statefulFlow = Flow[immutable.SortedSet[(AddUser, Promise[Reply])]]
      .buffer(1, OverflowStrategy.backpressure)
      .statefulMapConcat { () ⇒
        // mutable state
        var totalSize = 0L
        var localState: UserState0 = userState

        batch ⇒ {
          totalSize += batch.size
          localState = batch.foldLeft(userState)((state, c) ⇒ state.copy(state.users + c._1.id))

          println(s"********  size: ${batch.size}")
          scala.collection.immutable.Iterable(batch)
        }
      }

    val f = FlowWithContext[AddUser, Promise[Reply]]
      .withAttributes(Attributes.inputBuffer(1, 1))
      .mapAsync(bs) { cmd ⇒
        Future {
          Thread.sleep(ThreadLocalRandom.current.nextInt(50, 100))
          cmd
        }
      }
      .asFlow
      .batch(bs, {
        case (e, p) ⇒
          immutable.SortedSet.empty[(AddUser, Promise[Reply])]({ //Don't have to use SortedSet because mapAsync preserves the order
            case (a, b) ⇒ if (a._1.id < b._1.id) -1 else 1
          }).+((e, p))
      })(_ + _)
      .via(statefulFlow)
      //.scanAsync()
      .mapAsync(1) { batch ⇒
        Future {
          Thread.sleep(ThreadLocalRandom.current.nextInt(100, 200))

          var lastP: Promise[Reply] = null
          var lastCmd: AddUser = null

          //println("*************")
          val it = batch.iterator
          while (it.hasNext) {
            val (cmd, p) = it.next
            println(cmd)
            lastP = p
            lastCmd = cmd
          }
          //println("********* size1: " + batch.size)
          //println("*************")
          //println(s"Batch: ${batch.mkString(",")} - Last: ${lastCmd}")
          (Added(lastCmd.id), lastP)
        }
      }

    FlowWithContext.fromTuples(f)
  }

  def statefulBatchedFlow0(
    userState: UserState0, bs: Int
  )(implicit ec: ExecutionContext): FlowWithContext[Cmd, Promise[Reply], Reply, Promise[Reply], Any] = {

    def loopRb(i: Int, rb: RingBuffer[(Cmd, Promise[Reply])], reply: Reply, p: Promise[Reply]): (Reply, Promise[Reply]) = {
      val cp = rb.poll()
      if (cp.isEmpty) (reply, p)
      else {
        val (c, p) = cp.get
        c match {
          case AddUser(seqNum, _) ⇒ loopRb(i + 1, rb, Added(seqNum), p)
          case RmUser(seqNum, _) ⇒ loopRb(i + 1, rb, Added(seqNum), p)
        }
      }
    }

    def loop(i: Int, rb: RingBuffer[(Cmd, Promise[Reply])], s: UserState0, p: Promise[Reply]): (UserState0, Promise[Reply]) = {
      val cp = rb.poll()
      if (cp.isEmpty) (s, p)
      else {
        val (c, p) = cp.get
        c match {
          case AddUser(id, _) ⇒
            loop(i + 1, rb, s.copy(s.users + id, seqNum = id), p)
          case RmUser(_, id) ⇒
            loop(i + 1, rb, s.copy(s.users - id, seqNum = id), p)
        }
      }
    }

    val p = Promise[Reply]()
    val rbFlow = Flow[(Cmd, Promise[Reply])] //
      .conflateWithSeed({ cmd ⇒
        val rb = new RingBuffer[(Cmd, Promise[Reply])](bs)
        rb.add(cmd)
        rb
      }) { (rb, cmd) ⇒
        rb.add(cmd)
        rb
      }

    val f = FlowWithContext[Cmd, Promise[Reply]]
      .withAttributes(Attributes.inputBuffer(1, 1))
      .mapAsync(4) { cmd ⇒
        Future {
          Thread.sleep(ThreadLocalRandom.current.nextInt(20, 50))
          cmd
        }
      }
      .asFlow
      /*.via(rbFlow)
      .mapAsync(1) { rb ⇒
        Future {
          //Persist
          Thread.sleep(ThreadLocalRandom.current.nextInt(250, 300))
          val size = rb.size
          val replyP = loopRb(0, rb, Added(0), p)
          println(s"Size: ${size} -  Reply.seqNum: ${replyP._1}")
          replyP
        }
      }*/
      .batch(bs, {
        case (e, p) ⇒
          val rb = new RingBuffer[(Cmd, Promise[Reply])](bs)
          rb.add(e -> p)
          rb
      })({ (rb, e) ⇒
          rb.add(e)
          rb
      })
      .scan((userState, Promise[Reply]())) {
        case (stateWithPromise, rb) ⇒
          val size = rb.size
          val (s, p) = loop(0, rb, stateWithPromise._1, stateWithPromise._2)
          println(s"batch.size:$size state.seqNum:${s.seqNum}")
          (s, p)
      }
      .mapAsync(1) { stateWithPromise ⇒
        Future {
          //Persist
          Thread.sleep(ThreadLocalRandom.current.nextInt(250, 300))
          val state = stateWithPromise._1
          val p = stateWithPromise._2

          //println(s"Persist seqNum: ${state.seqNum}")
          (Added(state.seqNum), p)
        }
      }

    FlowWithContext.fromTuples(f)
  }

  def persist(userId: Long)(implicit ec: ExecutionContext) = {
    Future {
      println(s"persist: ${userId}")
      Thread.sleep(ThreadLocalRandom.current.nextInt(50, 100))
      if (userId != -1L) Seq(Added(userId)) else Seq.empty
    }
  }

  def main0(args: Array[String]): Unit = {
    println("********************************************")
    val bs = 1 << 3

    implicit val sys: ActorSystem = ActorSystem("streams")
    implicit val mat: Materializer = ActorMaterializer()

    implicit val sch = sys.scheduler
    implicit val ec = mat.executionContext

    val processor =
      Source.queue[(AddUser, Promise[Reply])](bs, OverflowStrategy.dropNew /*.backpressure*/ )
        .via(statefulBatchedFlow(UserState0(), bs))
        .to(Sink.foreach { case (reply, p) ⇒ p.trySuccess(reply) })
        //.withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()

    val f = produce0(1L, bs, processor).flatMap(_ ⇒ sys.terminate)
    Await.result(f, Duration.Inf)
  }

  def main(args: Array[String]): Unit = {
    val bs = 1 << 3

    implicit val sys: ActorSystem = ActorSystem("streams")
    implicit val mat: Materializer = ActorMaterializer()

    implicit val sch = sys.scheduler
    implicit val ec = mat.executionContext

    val processor =
      Source
        .queue[(Cmd, Promise[Reply])](bs, OverflowStrategy.dropNew)
        .via(statefulBatchedFlow0(UserState0(), bs))
        .toMat(Sink.foreach { case (replies, p) ⇒ p.trySuccess(replies) })(Keep.left)
        //.withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()

    val f = produce(1L, bs, processor).flatMap(_ ⇒ sys.terminate)
    Await.result(f, Duration.Inf)
  }

  def produce0(n: Long, bs: Int, queue: SourceQueueWithComplete[(AddUser, Promise[Reply])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Unit] = {
    val p = ExpiringPromise[Reply](1000.millis)
    queue.offer(AddUser(n) -> p)
      .flatMap {
        case Enqueued ⇒
          if (n % bs == 0 || n == 1) {
            println(s"await $n")
            p.future
              .flatMap { reply ⇒
                println(s"confirm batch: $reply")
                produce0(n + 1, bs, queue)
                //akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
              }
              .recoverWith {
                case err: Throwable ⇒
                  //retry
                  println(err.getMessage)
                  akka.pattern.after(50.millis, sch)(produce0(n, bs, queue))
              }
          } else produce0(n + 1, bs, queue) // akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
        //produce0(n + 1, bs, processor)

        case Dropped ⇒
          println(s"back off: ${n}")
          akka.pattern.after(5000.millis, sch)(produce0(n, bs, queue))
        case other ⇒
          println(s"Failed: $n")
          Future.failed(ProcessorError(other))
      }
  }

  def produce(n: Long, bs: Int, queue: SourceQueueWithComplete[(Cmd, Promise[Reply])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Long] = {
    val p = ExpiringPromise[Reply](1000.millis)
    queue.offer((AddUser(n), p))
      .flatMap {
        case Enqueued ⇒
          if (n % bs == 0 || n == 1) {
            p.future
              .flatMap { reply ⇒
                //println(s"confirm batch: $reply")
                produce(n + 1, bs, queue)
                //akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
              }
              .recoverWith {
                case err: Throwable ⇒
                  //retry
                  println(err.getMessage)
                  akka.pattern.after(50.millis, sch)(produce(n, bs, queue))
              }
          } else produce(n + 1, bs, queue)
        case Dropped ⇒
          println(s"Dropped: $n")
          akka.pattern.after(500.millis, sch)(produce(n, bs, queue))
        //Future.failed(ProcessorUnavailable("Unavailable"))
        case other ⇒
          println(s"Failed: $n")
          Future.failed(ProcessorError(other))
      }
  }
}
