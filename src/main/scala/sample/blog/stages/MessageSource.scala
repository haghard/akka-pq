package sample.blog.stages

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import akka.stream.scaladsl.Source
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler, StageLogging}
import akka.stream.{ActorMaterializer, Attributes, Outlet, SourceShape}
import org.slf4j.LoggerFactory
import sample.blog.stages.MessageSource.AssignStageActor

import scala.collection.immutable.Queue

object MessageSource {
  case class AssignStageActor(actorRef: ActorRef)

  def run = {
      val log = LoggerFactory.getLogger(getClass)
      implicit val system = ActorSystem("SampleActorStage")
      implicit val materializer = ActorMaterializer()

      case class Install(actorRef: ActorRef)

      val sourceFeeder: ActorRef = system.actorOf(Props(new Actor with Stash {
        def receive: Receive = {
          case _: String => stash()
          case s: Install =>
            unstashAll()
            context become active(s.actorRef)
        }

        def active(stageActor: ActorRef): Receive = {
          case msg: String =>
            log.info("sourceFeeder received message, forwarding to stage: {} ", msg)
            stageActor ! msg
        }
      }))

      val sourceGraph: MessageSource[String] = new MessageSource[String](sourceFeeder)
      val source: Source[String, _] = Source.fromGraph(sourceGraph)

      source.runForeach(msg => {
        log.info("Stream received message: {} ", msg)
      })

      sourceFeeder ! "One"
      sourceFeeder ! "Two"
      sourceFeeder ! "Three"
  }

}

/*
 A custom graph stage to create a Source using getActorStage
 The end result is being able to send actor messages to a Source, for a stream to react to.
 */
class MessageSource[T](sourceFeeder: ActorRef) extends GraphStage[SourceShape[String]] {
  val out: Outlet[String] = Outlet("MessageSource")
  override val shape: SourceShape[String] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      lazy val self: StageActor = getStageActor(onMessage)
      var messages: Queue[T] = Queue()

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          log.info("onPull() called...")
          pump()
        }
      })

      private def pump(): Unit = {
        if (isAvailable(out) && messages.nonEmpty) {
          log.info("ready to dequeue")
          messages.dequeue match {
            case (msg: String, newQueue: Queue[T]) =>
              log.info("got message from queue, pushing: {} ", msg)
              push(out, msg)
              messages = newQueue
          }
        }
      }

      override def preStart(): Unit = {
        log.info("pre-starting stage, assigning StageActor to source-feeder")
        sourceFeeder ! AssignStageActor(self.ref)
      }

      private def onMessage(x: (ActorRef, Any)): Unit = {
        x match {
          case (_, msg: T) =>
            log.info("received msg, queueing: {} ", msg)
            messages = messages.enqueue(msg)
            pump()
          case (_, msg) =>
            throw new Exception("Unexpected message type")
        }
      }
    }
}
