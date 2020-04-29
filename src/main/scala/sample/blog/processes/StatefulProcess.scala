package sample.blog.processes

import java.util.concurrent.ThreadLocalRandom
import akka.actor.{ActorSystem, Scheduler}
import akka.stream.QueueOfferResult.{Dropped, Enqueued}
import akka.stream.scaladsl.{Flow, FlowWithContext, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorAttributes, ActorMaterializer, Attributes, Materializer, OverflowStrategy, QueueOfferResult}

import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

//runMain sample.blog.eg.StatefulProcess
object StatefulProcess {

  case class ProcessorUnavailable(name: String)
    extends Exception(s"Processor $name cannot accept requests at this time!")

  case class ProcessorError(result: QueueOfferResult)
    extends Exception(s"Unexpected queue offer result: $result!")

  sealed trait Cmd {
    def ts: Long
  }

  final case class AddUser(id: Long, ts: Long = System.currentTimeMillis) extends Cmd

  final case class RmUser(id: Long, ts: Long = System.currentTimeMillis) extends Cmd

  sealed trait Evn

  sealed trait Reply

  final case class Added(id: Long) extends Reply

  final case class Removed(id: Long) extends Reply

  final case class UserState(users: Set[Long] = Set.empty, current: Long = -1L, p: Promise[Seq[Reply]] = null)

  def statefulBatchedFlow(
    initialState: UserState, bs: Int
  )(implicit ec: ExecutionContext): FlowWithContext[AddUser, Promise[Reply], Reply, Promise[Reply], Any] = {
    val atts = Attributes.inputBuffer(1, 1)
    /*
    TODO: Check it out
    val a = Flow[Int].map(_ * 2)
    val b = Flow[Int].statefulMapConcat { () =>
       in => {
        List(in)
       }
    }
    a.via(b)
    */

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

    val statefulFlow = Flow[immutable.SortedSet[(AddUser, Promise[Reply])]]
      .buffer(1, OverflowStrategy.backpressure)
      .statefulMapConcat { () ⇒

        // mutable state goes here
        var userState = initialState
        var totalSize = 0L

        batch ⇒ {
          userState = userState.copy(userState.users ++ batch.map(_._1.id))
          totalSize += batch.size
          println(s"********  size: ${batch.size}")
          List(batch)
        }
      }

    val f = FlowWithContext[AddUser, Promise[Reply]]
      .withAttributes(atts)
      .asFlow
      .mapAsync(bs) { cmd ⇒
        Future {
          Thread.sleep(ThreadLocalRandom.current.nextInt(50, 100))
          cmd
        }
      }
      .batch(bs, {
        case (e, p) ⇒
          immutable.SortedSet.empty[(AddUser, Promise[Reply])]({
            case (a, b) ⇒ if (a._1.id < b._1.id) -1 else 1
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
          //println(batch.mkString(","))
          //println("*************")

          //println(s"Batch: ${batch.mkString(",")} - Last: ${lastCmd}")
          (Added(lastCmd.id), lastP)
        }
      }

    FlowWithContext.fromTuples(f)
  }

  def stateFlow(
    userState: UserState
  )(implicit ec: ExecutionContext): FlowWithContext[Cmd, Promise[Seq[Reply]], Seq[Reply], Promise[Seq[Reply]], Any] = {
    /*FlowWithContext[Cmd, Promise[Seq[Reply]]].map {
      case AddUser(id) ⇒ Seq(Added(id))
      case RmUser(id)  ⇒ Seq(Removed(id))
    }*/

    val f = FlowWithContext[Cmd, Promise[Seq[Reply]]]
      .withAttributes(Attributes.inputBuffer(1, 1))
      .asFlow
      .scan(userState) {
        case (state, (cmd, p)) ⇒
          cmd match {
            case AddUser(_, id) ⇒ state.copy(state.users + id, id, p)
            case RmUser(_, id)  ⇒ state.copy(state.users - id, id, p)
          }
      }
      /*.map { state ⇒
        if (state.current != -1L) (Seq(Added(state.current)), state.p)
        else (Seq.empty, state.p)
      }*/
      .mapAsync(1) { state ⇒ persist(state.current).map(r ⇒ (r, state.p)) }

    FlowWithContext.fromTuples(f)
  }

  def persist(userId: Long)(implicit ec: ExecutionContext) = {
    Future {
      println(s"persist: ${userId}")
      Thread.sleep(ThreadLocalRandom.current.nextInt(50, 100))
      if (userId != -1L) Seq(Added(userId)) else Seq.empty
    }
  }

  def main(args: Array[String]): Unit = {
    println("********************************************")

    implicit val sys: ActorSystem = ActorSystem("streams")
    implicit val mat: Materializer = ActorMaterializer()

    implicit val sch = sys.scheduler
    implicit val ec = mat.executionContext

    val bs = 1 << 3

    val processor =
      Source.queue[(AddUser, Promise[Reply])](bs, OverflowStrategy.dropNew /*.backpressure*/ )
        .via(statefulBatchedFlow(UserState(), bs))
        .toMat(Sink.foreach {
          case (reply, p) ⇒
            //println("confirm batch: " + reply)
            p.trySuccess(reply)
        })(Keep.left)
        //.withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()

    /*
    Source
        .queue[(Cmd, Promise[Seq[Reply]])](1 << 2, OverflowStrategy.dropNew)
        .via(stateFlow(UserState()))
        .toMat(Sink.foreach {
          case (replies, p) ⇒
            println("replies: " + replies)
            p.trySuccess(replies)
        })(Keep.left)
        //.withAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .addAttributes(ActorAttributes.supervisionStrategy(akka.stream.Supervision.resumingDecider))
        .run()
    */

    //val f = produce(1L, processor).flatMap(_ ⇒ sys.terminate)

    val f = produce0(1L, bs, processor).flatMap(_ ⇒ sys.terminate)
    Await.result(f, Duration.Inf)
  }

  def produce0(n: Long, bs: Int, processor: SourceQueueWithComplete[(AddUser, Promise[Reply])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Unit] = {
    val p = ExpiringPromise[Reply](1000.millis)
    processor.offer((AddUser(n), p))
      .flatMap {
        case Enqueued ⇒
          if (n % bs == 0 || n == 1) {
            println(s"await $n")
            p.future
              .flatMap { reply ⇒
                println(s"confirm batch: $reply")
                produce0(n + 1, bs, processor)
                //akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
              }
              .recoverWith {
                case err: Throwable ⇒
                  //retry
                  println(err.getMessage)
                  akka.pattern.after(50.millis, sch)(produce0(n, bs, processor))
              }

          } else produce0(n + 1, bs, processor) // akka.pattern.after(50.millis, sch)(produce0(n + 1, bs, processor))
        //produce0(n + 1, bs, processor)

        case Dropped ⇒
          println(s"back off: ${n}")
          akka.pattern.after(5000.millis, sch)(produce0(n, bs, processor))
        case other ⇒
          println(s"Failed: $n")
          Future.failed(ProcessorError(other))
      }
  }

  def produce(id: Long, processor: SourceQueueWithComplete[(Cmd, Promise[Seq[Reply]])])(implicit ec: ExecutionContext, sch: Scheduler): Future[Long] = {
    if (id <= 100) {
      Future.traverse(Seq(id, id + 1, id + 2, id + 3)) { id ⇒
        val p = ExpiringPromise[Seq[Reply]](300.millis)
        processor.offer((AddUser(id), p))
          .flatMap {
            case Enqueued ⇒
              p.future.map(_ ⇒ id)
                .recoverWith {
                  case err: Throwable ⇒
                    println("Time-out: " + id + ":" + err.getMessage)
                    akka.pattern.after(500.millis, sch)(produce(id, processor))
                }
            case Dropped ⇒
              println(s"Dropped: $id")
              akka.pattern.after(500.millis, sch)(produce(id, processor))
            //Future.failed(ProcessorUnavailable("Unavailable"))
            case other ⇒
              println(s"Failed: $id")
              Future.failed(ProcessorError(other))
          }
      }.flatMap(ids ⇒ produce(ids.max, processor))
    } else Future.successful(id)
  }

}
