package sample.blog

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FlowShape, Inlet, Outlet}
import scala.collection.mutable

//http://doc.akka.io/docs/akka/2.5/scala/stream/stream-customize.html#Integration_with_actors

/*
 Rate decoupled graph stages
 Sometimes it is desirable to decouple the rate of the upstream and downstream of a stage, synchronizing only when needed.

 This is achieved in the model by representing a GraphStage as a boundary between two regions where the demand sent upstream is decoupled from the demand that arrives from downstream. One immediate consequence of this difference is that an onPush call does not always lead to calling push and an onPull call does not always lead to calling pull.

 One of the important use-case for this is to build buffer-like entities, that allow independent progress of upstream and downstream stages when the buffer is not full or empty, and slowing down the appropriate side if the buffer becomes empty or full.
*/
//ActorPub and ActorSub analogy
final class Processor[A] extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("TwoBuffer.in")
  val out = Outlet[A]("TwoBuffer.out")

  val shape = FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {

      val buffer = mutable.Queue[A]()

      def bufferFull = buffer.size == 2

      var downstreamWaiting = false

      override def preStart(): Unit = {
        // a detached stage needs to start upstream demand
        // itself as it is not triggered by downstream demand
        pull(in)
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          buffer.enqueue(elem)
          if (downstreamWaiting) {
            downstreamWaiting = false
            val bufferedElem = buffer.dequeue()
            push(out, bufferedElem)
          }
          if (!bufferFull) {
            pull(in)
          }
        }

        override def onUpstreamFinish(): Unit = {
          if (buffer.nonEmpty) {
            // emit the rest if possible
            emitMultiple(out, buffer.toIterator)
          }
          completeStage()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (buffer.isEmpty) {
            downstreamWaiting = true
          } else {
            val elem = buffer.dequeue
            push(out, elem)
          }
          if (!bufferFull && !hasBeenPulled(in)) {
            pull(in)
          }
        }
      })
    }
}
