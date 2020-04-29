package sample.blog.processes

import sample.blog.processes.Table0.GameTableEvent
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler, StageLogging }

/*
  Flow.fromGraph(new Chunker(chunkSize))
*/

final class EventBuffer(val watermark: Int) extends GraphStage[FlowShape[GameTableEvent, Vector[GameTableEvent]]] {

  val in = Inlet[GameTableEvent]("chunker.in")
  val out = Outlet[Vector[GameTableEvent]]("chunker.out")

  override val shape = FlowShape.of(in, out)

  override protected def initialAttributes: Attributes =
    Attributes.name("event-buffer")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {

      var isDownstreamRequested = false
      var buffer = Vector.empty[GameTableEvent]

      private def enoughSpace: Boolean =
        buffer.size < (2 * watermark)

      @inline def tryPullUpstream(): Unit =
        if (enoughSpace && !hasBeenPulled(in)) pull(in)

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (buffer.isEmpty)
            isDownstreamRequested = true
          else
            emitChunk()

          tryPullUpstream()
        }
      })

      setHandler(
        in,
        new InHandler {
          override def onPush(): Unit = {
            val event = grab(in)
            buffer = buffer.+:(event)

            if (isDownstreamRequested) {
              isDownstreamRequested = false
              emitChunk()
            }

            tryPullUpstream()
            //
          }

          override def onUpstreamFinish(): Unit =
            if (buffer.isEmpty) completeStage()
            else {
              if (isAvailable(out)) push(out, buffer)
            }
        }
      )

      private def emitChunk(): Unit = {
        if (isAvailable(out)) {
          val (batch, nextBuffer) = buffer.splitAt(watermark)
          buffer = nextBuffer
          push(out, batch)
        }
      }

      /*private def emitChunk0(): Unit = {
        /*if (buffer.nonEmpty && isAvailable(out)) {
          val (chunk, nextBuffer) = buffer.splitAt(chunkSize)
          buffer = nextBuffer
          push(out, chunk)
        } else {
          if (buffer.isEmpty)
            if (isClosed(in)) completeStage()
          if (!hasBeenPulled(in)) pull(in)
        }*/

        if (buffer.isEmpty) {
          if (isClosed(in)) completeStage() else pull(in)
        } else {
          if (isAvailable(out)) {
            val (chunk, nextBuffer) = buffer.splitAt(watermark)
            buffer = nextBuffer
            push(out, chunk)
          }
        }
      }*/
    }
}