package sample.blog

import akka.actor.SupervisorStrategy.Decider
import akka.actor.{Actor, ActorInitializationException, ActorLogging, ActorRef, ActorSystem, AllForOneStrategy, Props, SupervisorStrategy, Terminated, Timers}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.io.StdIn
import scala.util.control.NoStackTrace

class Observer(p: ActorRef) extends Actor with ActorLogging {
  context.watch(p)

  override def receive: Receive = {
    case Terminated(`p`) ⇒
      println(s"Observer observed $p termination")
      context.stop(self)
  }
}

class Parent extends Actor with ActorLogging {
  override def preStart(): Unit = {
    println("Parent preStart " + self.path)
  }

  override def postStop(): Unit = {
    println("Parent postStop")
  }

  override val supervisorStrategy: SupervisorStrategy = AllForOneStrategy()(
    {
      case ex: ActorInitializationException ⇒ //catches Child.FatalInitializationError
        println(s"Parent caught a child Initialization failure : ${ex.getMessage}")
        context.stop(self)
        akka.actor.SupervisorStrategy.Stop
      case ex: Throwable ⇒
        println(s"Parent caught a child failure ${ex.getClass.getName} :" + ex.getMessage)
        context.stop(self)
        akka.actor.SupervisorStrategy.Stop
    }: Decider
  )

  context.actorOf(Props(new Child), "child-a")

  override def receive: Receive = Actor.emptyBehavior
}

object Child {

  final case class ChildInitializationError(
    actor: ActorRef,
    message: String,
    cause: Throwable
  ) extends ActorInitializationException(actor, message, cause) with NoStackTrace
  
}


class Child extends Actor with ActorLogging with Timers {
  implicit val ec = context.dispatcher

  override def postStop(): Unit = {
    println("Child postStop")
  }

  def async() = Future {
    Thread.sleep(5000)
    throw Child.ChildInitializationError(self, "Fatal error", new Exception("Init boom !!!!"))

    //throw new Exception("Boom !!!!!!!!!!!")
    1
  }

  //val r = Await.result(async, Duration.Inf)
  import akka.pattern.pipe
  async().pipeTo(self)

  override def preStart(): Unit = {
    println("Child preStart " + self.path + " parent:" + context.parent.path)
    import scala.concurrent.duration._
    timers.startTimerAtFixedRate("xxx", Symbol("Work"), 2.seconds)
    //context.system.scheduler.schedule(0.seconds, 3.seconds)(self ! Symbol("Work"))
  }

  override def receive: Receive = {
    case akka.actor.Status.Failure(ex) ⇒
      println(s"Failure in child:" + ex.getMessage)
      throw ex
    case Symbol("Work") ⇒
      println("work")
  }
}

//sample.blog.SupervisionApp
object SupervisionApp {

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.empty()
    implicit val system = ActorSystem("table", config)

    val p = system.actorOf(Props(new Parent), "parent")
    system.actorOf(Props(new Observer(p)), "obs")

    StdIn.readLine()
  }
}