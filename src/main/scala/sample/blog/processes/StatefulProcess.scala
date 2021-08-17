package sample.blog.eg

import java.util.concurrent.ThreadLocalRandom
import akka.Done
import akka.actor.{ ActorSystem, Scheduler }
import akka.stream.QueueOfferResult.{ Dropped, Enqueued }
import akka.stream.scaladsl.{ Flow, FlowWithContext, Keep, Sink, Source, SourceQueueWithComplete, SourceWithContext }
import akka.stream.{ ActorAttributes, Attributes, OverflowStrategy, QueueOfferResult, ThrottleMode }
import sample.blog.processes.{ EventBuffer, ExpiringPromise, RingBuffer }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }

//runMain sample.blog.eg.StatefulProcess

/**
 *
 * https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
 *
 * https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
 * https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/
 * the corresponding talk https://youtu.be/MzosGtjJdPg
 *
 *
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-i/
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-ii/
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-iii/
 * https://blog.colinbreck.com/integrating-akka-streams-and-akka-actors-part-iv/
 *
 * https://blog.colinbreck.com/rethinking-streaming-workloads-with-akka-streams-part-ii/
 *
 * https://softwaremill.com/windowing-data-in-akka-streams/
 *
 */
object StatefulProcess {

  case class ProcessorUnavailable(name: String)
    extends Exception(s"Processor $name cannot accept requests at this time!")

  case class ProcessorError(result: QueueOfferResult)
    extends Exception(s"Unexpected queue offer result: $result!")

  sealed trait Cmd {
    def seqNum: Long

    def userId: Long
  }

  final case class AddUser(seqNum: Long, userId: Long = System.currentTimeMillis) extends Cmd

  final case class RmUser(seqNum: Long, userId: Long = System.currentTimeMillis) extends Cmd

  sealed trait Evn

  sealed trait Reply

  final case class Added(seqNum: Long) extends Reply

  final case class Removed(seqNum: Long) extends Reply

  final case class UserState(users: Set[Long] = Set.empty, current: Long = -1L)

  final case class UserState0(users: Set[Long] = Set.empty, nextSeqNum: Long = 1L)

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
          localState = batch.foldLeft(userState)((state, c) ⇒ state.copy(state.users + c._1.seqNum))

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
            case (a, b) ⇒ if (a._1.seqNum < b._1.seqNum) -1 else 1
          }).+((e, p))
      })(_ + _)
      .via(statefulFlow)
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
          (Added(lastCmd.seqNum), lastP)
        }
      }

    FlowWithContext.fromTuples(f)
  }

  def statefulFlow(
    userState: UserState0, bs: Int
  )(implicit ec: ExecutionContext): FlowWithContext[Cmd, Promise[Reply], Reply, Promise[Reply], Any] = {

    def applyCmd(cmd: Cmd, state: UserState0): UserState0 = {
      cmd match {
        case AddUser(seqNum, id) ⇒
          if (state.nextSeqNum == seqNum) state.copy(state.users + id, state.nextSeqNum + 1)
          else state //println(state.nextSeqNum + ":" + seqNum + " duplicate")
        case RmUser(seqNum, id) ⇒
          if (state.nextSeqNum == seqNum) state.copy(state.users - id, state.nextSeqNum + 1)
          else state //println(state.nextSeqNum + ":" + seqNum + " duplicate")
      }
    }

    //1. `mapAsync` fan-out stage that introduces asynchrony
    //2. `scan` foreach cmd we sequentially applies f(state, cmd) => (state, promise)
    //3. `mapAsync(1)` persist state resulted from applying one event
    val f = Flow.fromMaterializer { (mat, attr) ⇒
      val ec = mat.executionContext
      val disp = attr.get[ActorAttributes.Dispatcher].get
      val buf = attr.get[akka.stream.Attributes.InputBuffer].get
      //println("attributes: " + attr.attributeList.mkString(","))

      FlowWithContext[Cmd, Promise[Reply]]
        .withAttributes(Attributes.inputBuffer(bs, bs))
        //The mapAsync flow stage introduces asynchrony because the future will be executed on a thread of the execution context,
        //rather than in-line by the actor executing the flow stage — but it does not introduce an asynchronous boundary into the flow.
        .mapAsync(bs) { cmd ⇒
          Future {
            //enrich commands
            Thread.sleep(ThreadLocalRandom.current.nextInt(20, 150))
            cmd
          }(ec)
        }
        .asFlow
        //When an asynchronous boundary is introduced, it inserts a buffer between asynchronous processing stage,
        //to support a windowed backpressure-strategy, where new elements are requested in batches, to amortize the cost
        //of requesting elements across the asynchronous boundary between stages.
        .async(disp.dispatcher, buf.max)
        .scan((userState, Promise[Reply]())) {
          case (stateWithPromise, elem) ⇒
            val cmd = elem._1
            val p = elem._2
            val state = stateWithPromise._1
            val updatedState = applyCmd(cmd, state)
            (updatedState, p)
        }
        .mapAsync(1) { stateWithPromise ⇒
          Future {
            //Persist
            Thread.sleep(ThreadLocalRandom.current.nextInt(250, 300))
            val state = stateWithPromise._1
            val p = stateWithPromise._2

            println(s"Persist ${state.nextSeqNum}")
            (Added(state.nextSeqNum), p)
          }(ec)
        }
    }
    FlowWithContext.fromTuples(f)
  }

  def statefulBatchedFlow1(
    userState: UserState0, bs: Int
  )(implicit ec: ExecutionContext): FlowWithContext[Cmd, Promise[Reply], Reply, Promise[Reply], Any] = {
    //returns new state and last promise
    def updateState(i: Int, rb: RingBuffer[(Cmd, Promise[Reply])], s: UserState0, p: Promise[Reply]): (UserState0, Promise[Reply]) = {
      val cp = rb.poll()
      if (cp.isEmpty) (s, p)
      else {
        val (c, p) = cp.get
        c match {
          case AddUser(seqNum, id) ⇒
            if (s.nextSeqNum == seqNum) {
              println(s.nextSeqNum + ":" + seqNum)
              updateState(i + 1, rb, s.copy(s.users + id, s.nextSeqNum + 1), p)
            } else {
              println(s.nextSeqNum + ":" + seqNum + " duplicate")
              updateState(i + 1, rb, s, p)
            }
          case RmUser(seqNum, id) ⇒
            if (s.nextSeqNum == seqNum) updateState(i + 1, rb, s.copy(s.users - id, s.nextSeqNum + 1), p)
            else updateState(i + 1, rb, s, p)
        }
      }
    }

    //1. `mapAsync` fan-out stage which reserves order as received from upstream
    //2. `batch` collect enriched commands in a buffer
    //3. `scan` foreach cmd we sequentially run f(state, cmd) => (state, promise)
    //4. `mapAsync(1)` persist state resulted from applying the batch
    val f = FlowWithContext[Cmd, Promise[Reply]]
      .withAttributes(Attributes.inputBuffer(bs, bs))
      .mapAsync(bs) { cmd ⇒
        Future {
          //enrich commands
          Thread.sleep(ThreadLocalRandom.current.nextInt(20, 50))
          cmd
        }
      }
      .asFlow
      // The performance gain comes from keeping the downstream more saturated,
      // especially if it's not uniform workload
      .batch(bs, {
        case (e, p) ⇒
          val rb = new RingBuffer[(Cmd, Promise[Reply])](bs)
          rb.offer(e -> p)
          rb
      })({ (rb, e) ⇒
        rb.offer(e)
        rb
      })
      .scan((userState, Promise[Reply]())) {
        case (stateWithPromise, rb) ⇒
          val currState = stateWithPromise._1
          val size = rb.size
          val (state, p) = updateState(0, rb, currState, stateWithPromise._2)
          println(s"batch.size:$size  state.seqNum:${state.nextSeqNum}")
          (state, p)
      }
      .mapAsync(1) { stateWithPromise ⇒
        Future {
          //Persist
          Thread.sleep(ThreadLocalRandom.current.nextInt(250, 300))
          val state = stateWithPromise._1
          val p = stateWithPromise._2

          println(s"Persist seqNum: ${state.nextSeqNum}")
          (Added(state.nextSeqNum), p)
        }
      }
    FlowWithContext.fromTuples(f)
  }

  def statefulBatchedFlow0(
    userState: UserState0, bs: Int
  )(implicit ec: ExecutionContext): FlowWithContext[Cmd, Promise[Reply], Reply, Promise[Reply], Any] = {

    def loop(i: Int, rb: RingBuffer[(Cmd, Promise[Reply])], reply: Reply, p: Promise[Reply]): (Reply, Promise[Reply]) = {
      val cp = rb.poll()
      if (cp.isEmpty) (reply, p)
      else {
        val (c, p) = cp.get
        c match {
          case AddUser(seqNum, _) ⇒ loop(i + 1, rb, Added(seqNum), p)
          case RmUser(seqNum, _)  ⇒ loop(i + 1, rb, Added(seqNum), p)
        }
      }
    }

    // the performance gain come from keeping the downstream more saturated
    val rbFlow = Flow[(Cmd, Promise[Reply])] //
      .conflateWithSeed({ cmd ⇒
        val rb = new RingBuffer[(Cmd, Promise[Reply])](bs)
        rb.offer(cmd)
        rb
      }) { (rb, cmd) ⇒
        rb.offer(cmd)
        rb
      }

    val f = FlowWithContext[Cmd, Promise[Reply]]
      .withAttributes(Attributes.inputBuffer(1, 1))
      .mapAsync(bs) { cmd ⇒
        Future {
          Thread.sleep(ThreadLocalRandom.current.nextInt(20, 50))
          cmd
        }
      }
      .asFlow
      .via(rbFlow)
      .mapAsync(1) { rb ⇒
        Future {
          //Persist
          Thread.sleep(ThreadLocalRandom.current.nextInt(250, 300))
          val size = rb.size
          val replyWithPromise = loop(0, rb, Added(0), Promise[Reply]())
          println(s"Size: $size -  Reply.seqNum: ${replyWithPromise._1}")
          replyWithPromise
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

    implicit val sys: ActorSystem = ActorSystem("stateful-streams")
    implicit val sch = sys.scheduler
    implicit val ec = sys.dispatcher

    val processor =
      Source
        .queue[(AddUser, Promise[Reply])](bs, OverflowStrategy.dropNew /*.backpressure*/ )
        .via(statefulBatchedFlow(UserState0(), bs))
        .to(Sink.foreach { case (reply, p) ⇒ p.trySuccess(reply) })
        .withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        //.addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()

    val f = produce0(1L, bs, processor).flatMap(_ ⇒ sys.terminate)
    Await.result(f, Duration.Inf)
  }

  //confirms in batches
  def main1(args: Array[String]): Unit = {
    val bs = 1 << 2 //maxInFlight

    implicit val sys: ActorSystem = ActorSystem("stateful-streams")
    implicit val sch = sys.scheduler
    implicit val ec = sys.dispatcher

    //long running stateful stream
    val queue =
      Source
        .queue[(Cmd, Promise[Reply])](bs, OverflowStrategy.backpressure)
        .via(statefulBatchedFlow1(UserState0(), bs))
        .toMat(Sink.foreach { case (replies, p) ⇒ p.trySuccess(replies) })(Keep.left)
        .withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        //.addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()

    val f = produce(1L, bs, queue).flatMap(_ ⇒ sys.terminate)
    Await.result(f, Duration.Inf)
  }

  //confirms one by one
  def main(args: Array[String]): Unit = {
    val bs = 1 << 2

    implicit val sys: ActorSystem = ActorSystem("stateful-streams")
    implicit val sch = sys.scheduler
    implicit val ec = sys.dispatcher

    //https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
    //SourceWithContext.fromTuples(Source.queue[(Cmd, Promise[Reply])](bs))
    Source.queue[(Cmd, Promise[Reply])](bs)
      .via(statefulFlow(UserState0(), bs))
      .toMat(Sink.foreach { case (replies, p) ⇒ p.trySuccess(replies) })(Keep.left)
      .withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
      .run()

    val queue =
      Source
        .queue[(Cmd, Promise[Reply])](bs, OverflowStrategy.dropNew)
        .via(statefulFlow(UserState0(), bs))
        .toMat(Sink.foreach { case (replies, p) ⇒ p.trySuccess(replies) })(Keep.left)
        .withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        //.addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()

    val f = produceOneWithinStream(queue).flatMap(_ ⇒ sys.terminate)
    //val f = produceOne(1L, queue).flatMap(_ ⇒ sys.terminate)
    Await.result(f, Duration.Inf)
  }

  def hash(k: String): Int =
    akka.util.Unsafe.fastHash(k)

  def produce0(n: Long, maxInFlight: Int, queue: SourceQueueWithComplete[(AddUser, Promise[Reply])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Unit] = {
    val confirmationTimeout = 1000.millis //delivery of the whole batch should be confirmed withing this timeout.
    //What we're saying here is that within this timeout we are able to handle batch of messages. Basically we confirm in batches

    //The flow control is driven by the consumer side, which means that the producer will not send faster than consumer can confirm the batches
    val p = ExpiringPromise[Reply](confirmationTimeout)
    queue.offer(AddUser(n) -> p)
      .flatMap {
        case Enqueued ⇒
          if (n % maxInFlight == 0 || n == 1) {
            println(s"await $n")
            p.future
              .flatMap { reply ⇒
                println(s"confirm batch: $reply")
                produce0(n + 1, maxInFlight, queue)
                //akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
              }
              .recoverWith {
                case err: Throwable ⇒
                  //retry the whole last batch, therefore deduplication is required
                  println(err.getMessage)
                  akka.pattern.after(1000.millis, sch)(produce0(n - maxInFlight, maxInFlight, queue))
              }
          } else produce0(n + 1, maxInFlight, queue) // akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
        //produce0(n + 1, bs, processor)

        case Dropped ⇒
          println(s"back off: $n")
          akka.pattern.after(5000.millis, sch)(produce0(n, maxInFlight, queue))
        case other ⇒
          println(s"Failed: $n")
          Future.failed(ProcessorError(other))
      }
  }

  def produce(n: Long, maxInFlight: Int, queue: SourceQueueWithComplete[(Cmd, Promise[Reply])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Long] = {
    val confirmationTimeout = 600.millis //delivery of batch should be confirmed withing this timeout
    //What we're saying here is that within this timeout we are able to handle batch of messages. Basically we confirm in batches

    //The flow control is driven by the consumer side, which means that the producer will not send faster than consumer can confirm
    val p = ExpiringPromise[Reply](confirmationTimeout)
    queue.offer(AddUser(n) -> p)
      .flatMap {
        case Enqueued ⇒
          if (n % maxInFlight == 0) {
            p.future
              .flatMap { reply ⇒
                //println(s"confirm batch: $reply")
                //confirm in batches
                produce(n + 1, maxInFlight, queue)
              }
              .recoverWith {
                case err: Throwable ⇒
                  //retry the whole last batch, therefore deduplication is required
                  println("Error " + err.getMessage + " Retry the whole last batch")
                  akka.pattern.after(1000.millis, sch)(produce(n - maxInFlight, maxInFlight, queue))
              }
          } else produce(n + 1, maxInFlight, queue)
        case Dropped ⇒
          println(s"Dropped: $n")
          akka.pattern.after(500.millis, sch)(produce(n, maxInFlight, queue))
        //Future.failed(ProcessorUnavailable("Unavailable"))
        case other ⇒
          println(s"Failed: $n")
          Future.failed(ProcessorError(other))
      }
  }

  def produceOneWithinStream(queue: SourceQueueWithComplete[(Cmd, Promise[Reply])])(implicit sys: ActorSystem): Future[Done] = {
    val maxLatency = 400.millis

    def submit(cmd: Cmd, queue: SourceQueueWithComplete[(Cmd, Promise[Reply])]): Future[Reply] = {
      implicit val ec = sys.dispatcher
      implicit val sch = sys.scheduler
      val p = ExpiringPromise[Reply](maxLatency)
      queue
        .offer(cmd -> p)
        .flatMap {
          case Enqueued ⇒
            p.future
              .recoverWith {
                case err: Throwable ⇒
                  //retry the last send command, therefore deduplication is required.
                  val retryEvent = cmd.seqNum - 1
                  println(s"Error: ${err.getMessage}. Retry event: $retryEvent")
                  akka.pattern.after(maxLatency, sys.scheduler)(submit(cmd, queue))
              }
          case Dropped ⇒
            println(s"Dropped: ${cmd.seqNum}")
            akka.pattern.after(maxLatency, sys.scheduler)(submit(cmd, queue))
          case other ⇒
            println(s"Failed: ${cmd.seqNum}")
            Future.failed(ProcessorError(other))
        }
    }

    Source
      .fromIterator(() ⇒ Iterator.from(1).map(seqNum ⇒ AddUser(seqNum.toLong)))
      .throttle(10, maxLatency) //10 per maxLatency
      .mapAsync(1)(cmd ⇒ submit(cmd, queue))
      .run()
  }

  def produceOne(seqNum: Long, queue: SourceQueueWithComplete[(Cmd, Promise[Reply])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Long] = {
    val maxLatency = 400.millis
    val p = ExpiringPromise[Reply](maxLatency)

    queue
      .offer(AddUser(seqNum) -> p)
      .flatMap {
        case Enqueued ⇒
          p.future
            .flatMap(reply ⇒ produceOne(seqNum + 1, queue))
            .recoverWith {
              case err: Throwable ⇒
                //retry the last send command, therefore deduplication is required.
                val retryEvent = seqNum - 1
                println(s"Error: ${err.getMessage}. Retry event: $retryEvent")
                akka.pattern.after(maxLatency, sch)(produceOne(seqNum - 1, queue))
            }
        case Dropped ⇒
          println(s"Dropped: $seqNum")
          akka.pattern.after(maxLatency, sch)(produceOne(seqNum, queue))
        case other ⇒
          println(s"Failed: $seqNum")
          Future.failed(ProcessorError(other))
      }
  }
}