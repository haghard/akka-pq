package sample.blog.stages

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}
import akka.stream.{ActorMaterializer, Attributes, Outlet, SourceShape}
import org.slf4j.LoggerFactory
import sample.blog.stages.ActorSource.InstallActorRef

import scala.collection.immutable.Queue
import scala.collection.mutable

object ActorSource {
  case class InstallActorRef(actorRef: ActorRef)

  def run = {
      val log = LoggerFactory.getLogger(getClass)
      implicit val system = ActorSystem("SampleActorStage")
      implicit val materializer = ActorMaterializer()

      val actor: ActorRef = system.actorOf(Props(new Actor with Stash {
        def receive: Receive = {
          case _: String => stash()
          case s: InstallActorRef =>
            unstashAll()
            context become active(s.actorRef)
        }

        def active(actor: ActorRef): Receive = {
          case msg: String =>
            log.info("Actor received message, forwarding to stream: {} ", msg)
            actor ! msg
        }
      }))

      val sourceGraph: ActorSource = new ActorSource(actor)
      val source: Source[String, _] = Source.fromGraph(sourceGraph)

      source.runForeach(msg => {
        log.info("Stream received message: {} ", msg)
      })

      actor ! "One"
      actor ! "Two"
      actor ! "Three"
  }

}

/*
 A custom graph stage to create a Source using getActorStage
 The end result is being able to send actor messages to a Source, for a stream to react to.
 */
class ActorSource(source: ActorRef) extends GraphStage[SourceShape[String]] {
  val out: Outlet[String] = Outlet("MessageSource")
  override val shape: SourceShape[String] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      lazy val actorStage: StageActor = getStageActor(onMessage)
      val buffer  = mutable.Queue[String]()

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          log.info("onPull() called...")
          tryToPush()
        }
      })

      private def tryToPush(): Unit = {
        if (isAvailable(out) && buffer.nonEmpty) {
          log.info("ready to dequeue")
          val bufferedElem = buffer.dequeue
          push(out, bufferedElem)
        }
      }

      override def preStart(): Unit = {
        log.info("pre-starting stage, assigning StageActor to source-feeder")
        source ! InstallActorRef(actorStage.ref)
      }

      private def onMessage(x: (ActorRef, Any)): Unit = {
        x._2 match {
          case msg: String =>
            log.info("received msg, queueing: {} ", msg)
            buffer enqueue msg
            tryToPush()
          case other =>
            failStage(throw new Exception(s"Unexpected message type ${other.getClass.getSimpleName}"))
        }
      }
    }
}
