package sample.blog.processes

import akka.actor.{ ActorLogging, ActorRef, Props, Timers }
import akka.persistence.PersistentActor
import akka.stream.scaladsl.RestartWithBackoffFlow
import sample.blog.processes.Table0._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._

/**
 * High-throughput persistent actor that uses batching to persist.
 * The goal of this is to adapt to dynamic workload.
 *
 *
 * https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
 * https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/
 * https://blog.colinbreck.com/akka-streams-a-motivating-example/
 *
 * https://youtu.be/MzosGtjJdPg
 *
 *
 */
object Table0 {

  sealed trait GameTableCmd {
    def cmdId: Long
  }

  final case class Inc(cmdId: Long) extends GameTableCmd

  sealed trait GameTableEvent
  final case class Incremented(cmdId: Long) extends GameTableEvent

  sealed trait GameTableReply
  final case class BackOff(cmd: Inc) extends GameTableReply

  final case class BetPlacedReply(cmdId: Long)

  case object Flush

  //final case class State(userChips: Map[Long, Int] = Map.empty)
  final case class State(counter: Long)

  final case class Persisted(cmdId: Long)

  def props(ind: Int) = Props(new Table0(ind))
}

/**
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
class Table0(ind: Int, waterMark: Int = 8) extends Timers with PersistentActor with ActorLogging {

  private val flushInterval = 2000.millis

  override val persistenceId = "table-0" //self.path.name

  timers.startTimerAtFixedRate(persistenceId, Flush, flushInterval)

  override def receiveRecover: Receive = {
    var counter = 0L

    {
      case _: Incremented ⇒
        counter = counter + ind
      case akka.persistence.RecoveryCompleted ⇒
        val state = State(counter)
        log.info("{} RecoveryCompleted {}", ind, state.counter)
        context.become(active(SortedMap[Long, GameTableEvent](), state, None, Set.empty[Long]))
    }
  }

  override def receiveCommand: Receive =
    active(SortedMap[Long, GameTableEvent](), State(0L), None, Set.empty[Long])

  //Invariant: Number of chips per player should not exceed 100
  def update(event: GameTableEvent, state: State): State =
    event match {
      case _: Incremented ⇒
        state.copy(counter = state.counter + ind)
      case other ⇒
        log.error(s"Unknown event $other")
        state
    }

  //we flush only when current persistInFlight buffer is empty
  def tryFlush(persistInFlight: Int): Unit = {
    if (persistInFlight == 0)
      self ! Flush
  }

  def active(
    outstandingEvents: SortedMap[Long, GameTableEvent], optimisticState: State,
    upstream: Option[ActorRef], persistInFlight: Set[Long]
  ): Receive = {
    case cmd: Table0.Inc ⇒
      log.warning("*** inc:{}", ind)
      if (outstandingEvents.keySet.size <= waterMark) {
        val ev = Incremented(cmd.cmdId)
        val updatedState = update(ev, optimisticState)
        val outstandingEventsUpdated = outstandingEvents + (ev.cmdId -> ev)
        context become active(outstandingEventsUpdated, updatedState, Some(sender()), persistInFlight)
      } else {
        upstream.foreach(_ ! BackOff(cmd))
        log.warning("BackOff cmd:{} buffers[{}] - [{}]", cmd.cmdId, outstandingEvents.keySet.mkString(","), persistInFlight.mkString(","))
        tryFlush(persistInFlight.size)
      }

    case Flush if outstandingEvents.nonEmpty ⇒
      log.info("{} Start persisting batch [{}]", ind, outstandingEvents.keySet.mkString(","))
      persistAllAsync(outstandingEvents.values.toSeq) { e ⇒
        e match {
          //calls for each persisted event
          case e: Incremented ⇒
            // Who knows how long to sleep ¯\_(ツ)_/¯?
            Thread.sleep(41)
            self ! Persisted(e.cmdId)
        }
      }
      context.become(active(SortedMap[Long, GameTableEvent](), optimisticState, upstream, outstandingEvents.keySet))

    case Persisted(cmdId) ⇒
      val inFlight = persistInFlight - cmdId
      //upstream.foreach(_ ! BetPlacedReply(cmdId))
      tryFlush(inFlight.size)
      context.become(active(outstandingEvents, optimisticState, upstream, inFlight))
  }
}
