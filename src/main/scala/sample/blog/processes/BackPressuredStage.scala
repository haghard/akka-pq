package sample.blog.processes

//import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet, Supervision }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging }

import scala.collection.mutable

/*
    Related links:
      https://doc.akka.io/docs/akka/current/stream/stream-customize.html
      https://doc.akka.io/docs/akka/current/stream/stream-customize.html#rate-decoupled-operators
      https://github.com/mkubala/akka-stream-contrib/blob/feature/101-mkubala-interval-based-rate-limiter/contrib/src/main/scala/akka/stream/contrib/IntervalBasedRateLimiter.scala

      Rate decoupled graph stages.
      The main point being is that an `onPush` does not always lead to calling `push` and
        an `onPull` call does not always lead to calling `pull`.
      We stop pulling upstream when the internal buffer is filled up.
      This stage does what default `Buffer` stage does`


      The most important use-case for this is to build buffer-like entities, that allow independent progress of upstream and downstream operators
      when the buffer is not full or empty, and slowing down the appropriate side if the buffer becomes empty or full.
  */

//See: new akka.stream.impl.fusing.Buffer
final class BackPressuredStage2[A](watermark: Int)
  //extends SimpleLinearGraphStage[A] {
  extends GraphStage[FlowShape[A, A]] {

  val in = Inlet[A](akka.event.Logging.simpleName(this) + ".in")
  val out = Outlet[A](akka.event.Logging.simpleName(this) + ".out")

  override val shape = FlowShape(in, out)

  override protected def initialAttributes: Attributes =
    Attributes.name("bpb")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler with StageLogging {
      override def onPush(): Unit = ???
      override def onPull(): Unit = ???
    }
}

final class BackPressuredStage[A](watermark: Int) extends GraphStage[FlowShape[A, A]] {

  val in = Inlet[A]("bp.in")
  val out = Outlet[A]("bp.in")
  val shape = FlowShape.of(in, out)

  override protected def initialAttributes: Attributes =
    Attributes.name("back-pressured-buffer")

  //.and(ActorAttributes.dispatcher(FixedDispatcher))
  //.and(ActorAttributes.dispatcher("akka.flow-dispatcher"))

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      //this if how we can access internal decider
      val decider = inheritedAttributes
        .get[akka.stream.ActorAttributes.SupervisionStrategy]
        .map(_.decider)
        .getOrElse(Supervision.stoppingDecider)

      var isDownstreamRequested = false

      //ActorRefBackpressureSinkStage uses `ArrayDeque` as an internal buffer
      //An implementation of a double-ended queue that internally uses a resizable circular buffer.
      //val fifo0: java.util.Deque[A] = new java.util.ArrayDeque[A]()

      val fifo = mutable.Queue[A]()

      private def enoughSpace: Boolean =
        fifo.size < watermark

      // a detached stage needs to start upstream demand itself as it's not triggered by downstream demand
      override def preStart(): Unit = pull(in)

      private final def tryPull(): Unit =
        if (enoughSpace && !hasBeenPulled(in))
          pull(in)

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            fifo enqueue elem //O(1)
            //fifo0.offer(elem)

            //Can't keep up with req rate!
            //if (fifo.size > 1) log.debug("{} Buffering: {}", Thread.currentThread.getName, fifo.size)

            if (isDownstreamRequested) {
              isDownstreamRequested = false
              val elem = fifo.dequeue
              //val elem = fifo0.poll()
              push(out, elem)
            }

            tryPull()
            //else log.debug("{} Buffer is filled up. Wait for demand from the downstream")
          }

          override def onUpstreamFinish(): Unit = {
            if (fifo.nonEmpty) {
              //emitMultiple(out, fifo0.iterator())

              // emit the rest if possible
              emitMultiple(out, fifo.iterator)
            }
            completeStage()
          }
        }
      )

      setHandler(
        out,
        new OutHandler {
          override def onPull(): Unit = {
            if (fifo.isEmpty)
              isDownstreamRequested = true
            else {
              val elem = fifo.dequeue //O(1)
              //emitMultiple()
              push(out, elem)
            }
            tryPull()
          }
        }
      )
    }
}
