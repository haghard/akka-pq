package sample.blog.stages

import akka.actor.{ Actor, ActorRef, ActorSystem, Props, Stash }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.stage.GraphStageLogic.StageActor
import akka.stream.stage._
import akka.stream._
import org.slf4j.LoggerFactory
import sample.blog.stages.ActorSource.InstallSource

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

object ActorSource {
  case class InstallSource(actorRef: ActorRef)

  def run = {
    val log = LoggerFactory.getLogger(getClass)
    implicit val system = ActorSystem("actor-stage")

    implicit val materializer = ActorMaterializer()

    val publisher: ActorRef = system.actorOf(Props(new Actor with Stash {
      def receive: Receive = {
        case _: String ⇒ stash()
        case s: InstallSource ⇒
          unstashAll()
          context become active(s.actorRef)
      }

      def active(actor: ActorRef): Receive = {
        case msg: String ⇒
          log.info("Actor received message, forwarding to stream: {} ", msg)
          actor ! msg
      }
    }))

    val source: Source[String, akka.NotUsed] =
      Source.fromGraph(new ActorSource(publisher))

    source.to(Sink.foreach(log.info("Stream received message: {} ", _)))

    publisher ! "One"
    publisher ! "Two"
    publisher ! "Three"
  }
}

/*
 A custom graph stage to create a Source using getActorStage
 The end result is being able to send a actor messages to a source.
 */
class ActorSource(actor: ActorRef) extends GraphStage[SourceShape[String]] {
  val out: Outlet[String] = Outlet("out")
  override val shape: SourceShape[String] = SourceShape(out)

  override def createLogic(attributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with StageLogging {
      println(attributes.attributeList.mkString(","))
      lazy val actorStage: StageActor = getStageActor(onReceive)
      val buffer = mutable.Queue[String]()

      override def preStart(): Unit = {
        log.info("pre-starting stage, assigning StageActor to source-feeder")
        actor ! InstallSource(actorStage.ref)
      }

      setHandler(
        out,
        new OutHandler {
          override def onDownstreamFinish(): Unit = {
            val result = buffer
            if (result.nonEmpty) {
              log.debug(
                "In order to avoid message lost we need to notify the upsteam that " +
                  "consumed elements cannot be handled")
              //1. actor ! result - resend maybe
              //2. store to internal DB
              completeStage()
            }
            completeStage()
          }

          override def onPull(): Unit = {
            log.info("downstream: pull")
            tryToPush()
          }
        }
      )

      def tryToPush(): Unit = {
        if (isAvailable(out) && buffer.nonEmpty) {
          val element = buffer.dequeue
          log.info(s"${buffer.size} push $element")
          push(out, element)
        }
      }

      def onReceive(x: (ActorRef, Any)): Unit = {
        x._2 match {
          case msg: String ⇒
            log.info("published: {} ", msg)
            buffer enqueue msg
          //tryToPush()
          case other ⇒
            failStage(
              throw new Exception(
                s"Unexpected message type ${other.getClass.getSimpleName}"))
        }
      }
    }
}

//Emit an element once in silencePeriod
class TimedGate[A](silencePeriod: FiniteDuration) extends GraphStage[FlowShape[A, A]] {
  val in = Inlet[A]("in")
  val out = Outlet[A]("out")
  val shape = FlowShape.of(in, out)

  override def createLogic(attributes: Attributes) =
    new TimerGraphStageLogic(shape) {
      var open = false
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val elem = grab(in)
          if (open) {
            //drop elem
            pull(in)
          } else {
            push(out, elem)
            open = true
            scheduleOnce(None, silencePeriod)
          }
        }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })

      override protected def onTimer(timerKey: Any): Unit = {
        open = false
      }
    }
}
